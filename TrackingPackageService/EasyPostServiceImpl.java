package sneakpeeq.core.service;

import com.easypost.model.Event;
import com.easypost.model.EventDeserializer;
import com.easypost.model.Tracker;
import com.google.common.base.Preconditions;
import org.springframework.transaction.annotation.Transactional;
import sneakpeeq.core.daos.TrackingStatusDao;
import sneakpeeq.core.entity.OrderLineItemDTO;
import sneakpeeq.core.entity.ShippingMethodDTO;
import sneakpeeq.core.entity.ShippingParcelDTO;
import sneakpeeq.core.entity.ShippingProfileDTO;
import sneakpeeq.core.entity.TrackingStatusDTO;
import sneakpeeq.core.entity.shared.AddressDTO;
import sneakpeeq.core.entity.shared.enums.ShippingCarrier;
import sneakpeeq.core.exceptions.FAS.AddressVerificationException;
import sneakpeeq.core.exceptions.SPUserFacingShortFormException;
import sneakpeeq.core.managers.FAS.ShipMethodManager;
import sneakpeeq.core.managers.TransactionManager;
import sneakpeeq.core.models.FAS.TrackingStatus;
import sneakpeeq.core.models.FAS.WarehouseShipMethod;
import sneakpeeq.core.models.MailAddress;
import sneakpeeq.core.models.ShippingMethod;
import sneakpeeq.core.models.fulfillment.ServiceProvider;
import sneakpeeq.core.models.fulfillment.ShipmentDTO;
import sneakpeeq.core.models.fulfillment.billing.ShipEvent;
import sneakpeeq.core.pojos.PackageInfo;
import sneakpeeq.core.util.ConcurrentCollectionException;
import sneakpeeq.core.util.ConcurrentCollectionUtil;
import sneakpeeq.core.util.MoneyUtils;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import com.easypost.EasyPost;
import com.easypost.exception.EasyPostException;
import com.easypost.model.Address;
import com.easypost.model.Rate;
import com.easypost.model.Shipment;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.symphonycommerce.lib.exceptions.SPException;
import org.elasticsearch.common.base.Joiner;
import org.elasticsearch.common.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;

@Repository
public class EasyPostServiceImpl implements EasyPostService {

	private static final Pattern PHONE_PATTERN = Pattern.compile(".*[\\d]+.*");
	private final Comparator<Rate> RATE_COMPARATOR =
		Comparator.comparingDouble(Rate::getRate);

	private Logger logger = LoggerFactory.getLogger(EasyPostService.class);
	@Value("${easypostKey}")
	private String apiKey;
	@Value("${smartpostHub}")
	private String defaultSmartpostHubId;
	@Autowired
	private ExecutorService executor;
	@Autowired
	private ShipMethodManager shipMethodManager;
	@Autowired
	private TrackingStatusDao trackingStatusDao;
	@Autowired
	private TransactionManager transactionManager;


	public BiFunction<Map<String,Object>,String,Optional<Address>>
		addressVerificationFunction = (params,carrier) -> {
		try {
			Address address = Address.create(params);
			Address createdAddress = address.verifyWithCarrier(carrier);
			return Optional.of(createdAddress);
		}catch (EasyPostException e){
			logger.error(String.format("Failed to create carrier verified address", e));
			return Optional.empty();
		}
	};


	@PostConstruct
	public void postConstruct() {
		EasyPost.apiKey = apiKey;
	}

	private Object nullToUnavailable(Object x) {
		return x == null ? "unavailable" : x;
	}

	private String mergeFirstAndLastNames(String first, String last) {
		if (first == null && last == null) {
			return null;
		}
		return Joiner.on(" ").skipNulls().join(first, last);
	}

	private String validatePhoneNumber(String phone){
		return phone == null
			? null
			:PHONE_PATTERN.matcher(phone).matches()
			? phone
			: null;
	}

	private String getCompanyName(AddressDTO address) {
		return (address.getCompany() != null) ? address.getCompany()
			: mergeFirstAndLastNames(address.getFirstName(), address.getLastName());
	}

	private Map<String,Object> getAddress(AddressDTO address) throws EasyPostException {
		Map<String, Object> fromAddressMap = Maps.newHashMap();
		String name = mergeFirstAndLastNames(address.getFirstName(),
			address.getLastName());
		if(name != null){
			fromAddressMap.put("name", name);
		}
		if(address.getCompany() != null){
			fromAddressMap.put("company", address.getCompany());
		}
		fromAddressMap.put("street1", address.getStreet1());
		if(address.getStreet2() != null){
			fromAddressMap.put("street2", address.getStreet2());
		}
		fromAddressMap.put("city", address.getCity());
		fromAddressMap.put("state", address.getState());
		fromAddressMap.put("country", address.getCountry());
		fromAddressMap.put("zip", address.getZip());
		fromAddressMap.put("phone", validatePhoneNumber(address.getPhone()));
		return fromAddressMap;
	}

	private Map<String, Object> getParcel(ShippingParcelDTO parcel, BigDecimal weight)
		throws EasyPostException {

		Map<String, Object> returnMap = new HashMap<String, Object>();
		returnMap.put("weight", weight);

		if(parcel != null) {
			returnMap.put("height", parcel.getHeight());
			returnMap.put("width", parcel.getWidth());
			returnMap.put("length", parcel.getLength());
		}

		return ImmutableMap.copyOf(returnMap);
	}

	private Map<String, Object> customsItemFrom(OrderLineItemDTO item) throws EasyPostException {
		return new ImmutableMap.Builder<String, Object>()
			.put("description", nullToUnavailable(item.getVariant().getCustomsDescription()))
			.put("quantity", item.getQuantity())
			.put("value", item.getCustomsValue())
			.put("weight", nullToUnavailable(item.getVariant().getWeightInOunces()))
			.put("origin_country", nullToUnavailable(item.getVariant().getCountryOfOrigin()))
			.put("hs_tariff_number", nullToUnavailable(item.getVariant().getHsTariffNumber()))
			.build();
	}

	private Map<String, Object> customsInfoOf(ShipmentDTO shipment) throws EasyPostException {

		List<Map<String, Object>> customsItemsList = Lists.newArrayList();
		for (OrderLineItemDTO item : shipment.getItems()) {
			customsItemsList.add(customsItemFrom(item));
		}

		return new ImmutableMap.Builder<String, Object>()
			.put("customs_certify", true)
			.put("customs_signer", nullToUnavailable(shipment.getFromAddress().getCompany()))
			.put("contents_type", shipment.isReturnedGoods() ? "returned_goods" : "merchandise")
			.put("eel_pfc", "NOEEI 30.37(a)")
			.put("non_delivery_option", "return")
			.put("restriction_type", "none")
			.put("customs_items", customsItemsList)
			.build();

	}

	private Shipment createShipment(ShipmentDTO dto, ShippingProfileDTO profile) throws EasyPostException {
		return createShipment(dto, profile, true, null);
	}

	private Shipment createShipment(ShipmentDTO dto, ShippingProfileDTO profile, boolean includeCustoms, String smartpostHubId)
		throws EasyPostException {
		logger.info("Starting EasyPost shipment creation for ShipmentDTO: {} and ShippingProfileDTO: {}", dto, profile);

		dto.getFromAddress().setCompany(getCompanyName(dto.getFromAddress()));
		dto.getFromAddress().setFirstName(String.format("Shipment# %s", dto.getShipmentId()));

		if(validatePhoneNumber(dto.getFromAddress().getPhone()) == null) {
			dto.getFromAddress().setPhone("4156833653"); // Symphony Phone #
		}

		if(validatePhoneNumber(dto.getToAddress().getPhone()) == null) {
			dto.getToAddress().setPhone(dto.getFromAddress().getPhone());
		}

		Map<String, Object> shipmentOptions = new HashMap<String, Object>();
		if(smartpostHubId != null) {
			shipmentOptions.put("smartpost_hub", smartpostHubId);
		}
		if (dto.getToAddress().getResidential() == null || dto.getToAddress().getResidential()) {
			shipmentOptions.put("residential_to_address", 1);
		} else {
			shipmentOptions.put("residential_to_address", 0);
		}
		shipmentOptions.put("address_validation_level", "0");

		ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<String,Object>()
			.put("from_address", getAddress(dto.getFromAddress()))
			.put("to_address", getAddress(dto.getToAddress()))
			.put("parcel", getParcel(profile.getParcel(), profile.getWeight()))
			.put("reference", nullToUnavailable(dto.getShipmentId()))
			.put("options", shipmentOptions);

		if(!dto.getToAddress().isUnitedStates() && includeCustoms) {
			logger.info("Processing customs info for shipment {}", dto);

			Map<String, Object> customs_info = customsInfoOf(dto);
			builder.put("customs_info", customs_info);

			logger.info("Customs info : {} for shipment {}", customs_info, dto);
		}

		Map<String,Object> shipment = builder.build();
		logger.info("Created EasyPost shipment map {}", shipment);

		return Shipment.create(shipment);
	}

	private List<Rate> filteredRates(List<Rate> rates, final Set<String> acceptableServiceCodes) {
		return Lists.newArrayList(Iterables.filter(rates, new Predicate<Rate>() {
			@Override
			public boolean apply(Rate rate) {
				return acceptableServiceCodes.contains(rate.getServiceCode());
			}
		}));
	}

	@Override
	public List<ShippingMethodDTO> findAllRatesFor(ShipmentDTO dto, ShippingProfileDTO profile, String smartpostHubId)
		throws EasyPostException {

		List<ShippingMethodDTO> allMethods = Lists.newArrayList();

		smartpostHubId = smartpostHubId  == null ? defaultSmartpostHubId : smartpostHubId;

		Shipment shipment = createShipment(dto, profile, false, smartpostHubId);
		Map<String, ShippingMethodDTO> serviceCodeMethodMap = serviceCodeMethodMapFor(dto);

		for(Rate rate : filteredRates(shipment.getRates(), serviceCodeMethodMap.keySet())) {
			ShippingMethodDTO method = serviceCodeMethodMap.get(rate.getServiceCode());
			method.setRate(MoneyUtils.parseCentsFromDollars(rate.getRate().toString()).intValue());
			allMethods.add(method);
		}

		return allMethods;
	}

	@Override
	public ShippingMethodDTO cheapestShippingMethodFor(ShipmentDTO dto, ShippingProfileDTO profile)
		throws EasyPostException {
		Shipment easyPostShipment = createShipment(dto, profile);
		Map<String, ShippingMethodDTO> serviceCodeMethodMap = serviceCodeMethodMapFor(dto);
		List<Rate> sortedRates = getRatesSortedByPrice(easyPostShipment, serviceCodeMethodMap.keySet());

		if(sortedRates.isEmpty()) {
			return null;
		}

		Rate lowestRate = sortedRates.get(0);
		ShippingMethodDTO cheapestMethod = serviceCodeMethodMapFor(dto).get(lowestRate.getServiceCode());
		cheapestMethod.setRate(MoneyUtils.parseCentsFromDollars(lowestRate.getRate().toString()).intValue());
		return cheapestMethod;
	}

	private List<Rate> getRatesSortedByPrice(Shipment shipment, Set<String> serviceCodes)
		throws EasyPostException {
		logger.info("Easypost shipment.getRates {}", shipment.getRates());
		List<Rate> acceptableRates = filteredRates(shipment.getRates(), serviceCodes);
		List<Rate> sortedRates = Ordering.from(RATE_COMPARATOR).leastOf(acceptableRates, acceptableRates.size());
		List<String> easyPostRatesList = Lists.newArrayList();
		for(Rate rate : sortedRates) {
			easyPostRatesList.add("{shippingCode='"+rate.getServiceCode()+"', rate="+ rate.getRate()+"}");
		}
		logger.info("Sorted easypost rates for Shipment {} : {}", shipment.prettyPrint(), easyPostRatesList);
		return sortedRates;
	}

	private Map<String, ShippingMethodDTO> serviceCodeMethodMapFor(ShipmentDTO dto) {
		Map<String, ShippingMethodDTO> serviceCodeMethodMap = Maps.newHashMap();
		for (ShippingMethodDTO method : dto.getShippingOption().getShippingMethods()) {
			ShippingMethod easyPostShippingMethod = shipMethodManager.findBySymphonyCode(method.getSymphonyShippingCode());
			if(easyPostShippingMethod == null) {
				logger.warn("Ship Method {} doesn't have EASYPOST code", method.getSymphonyShippingCode());
				continue;
			}
			WarehouseShipMethod easypostWarehouseMethod = easyPostShippingMethod.getWarehouseShipMethod(ServiceProvider.EASYPOST);
			if(easypostWarehouseMethod!= null) {
				String easyPostShipCode = easypostWarehouseMethod.getWarehouseShipCode();
				serviceCodeMethodMap.put(easyPostShipCode, method);
			}
		}
		return serviceCodeMethodMap;
	}

	private Shipment buyShippingLabel(ShipmentDTO dto, ShippingProfileDTO profile) throws EasyPostException {

		Shipment shipment = createShipment(dto, profile);
		Map<String, ShippingMethodDTO> serviceCodeMethodMap = serviceCodeMethodMapFor(dto);
		List<Rate> sortedRates = getRatesSortedByPrice(shipment, serviceCodeMethodMap.keySet());

		for (Rate rate : sortedRates) {

			ShippingMethodDTO shippingMethodDTO = serviceCodeMethodMap.get(rate.getServiceCode());
			ShippingMethod shippingMethod =
				shipMethodManager.findBySymphonyCode(shippingMethodDTO.getSymphonyShippingCode());

			if(shippingMethod == null || shippingMethod.getWarehouseShipMethod(ServiceProvider.EASYPOST) == null) {
				throw new SPUserFacingShortFormException("Could not find a shipping method that suits this address.");
			}

			try {
				logger.info("Attempting to buy shipping label with rate {} for shipment {}",
					rate.prettyPrint(), shipment.prettyPrint());
				shipment = shipment.buy(rate);
				logger.info("Buying shipping label with rate {} for  shipment {}",
					rate.prettyPrint(), shipment.prettyPrint());
				shipment.setSelectedRate(rate);
				return shipment;
			} catch (EasyPostException e) {
				if (e.getMessage().equals("The Shippers shipper number cannot be used for the shipment")) {
					logger.warn(format("Unable to purchase rate, %s. Trying next rate.", rate.prettyPrint()), e);
				} else {
					throw e;
				}
			}
		}
		throw new SPUserFacingShortFormException("Could not find a purchasable shipping method.");
	}

	@Override
	public Map<ShipmentDTO, ShipEvent> batchBuyShippingLabel(
		Map<ShipmentDTO, ShippingProfileDTO> profileMap) throws ConcurrentCollectionException {

		logger.info("Batch buying shipping labels for {}", profileMap);

		Function<Entry<ShipmentDTO, ShippingProfileDTO>, Entry<ShipmentDTO, Shipment>> getShipment
			= entry -> {
			Map<ShipmentDTO, Shipment> shipmentMap = Maps.newLinkedHashMap();
			try {
				logger.info("Attempt to buy shipping label for entry {}", entry);
				shipmentMap.put(entry.getKey(),
					buyShippingLabel(entry.getKey(), entry.getValue()));
				return shipmentMap.entrySet().iterator().next();
			} catch (EasyPostException e) {
				throw new SPException(e);
			}
		};

		Function<Entry<ShipmentDTO, Shipment>, Boolean> refundShipment = entry -> {
			try {
				logger.info("Refunding Shipment {}", entry);
				entry.getValue().refund();
				return true;
			} catch (EasyPostException e) {
				logger.error("Easypost Error : ", e);
				return false;
			}
		};

		List<Entry<ShipmentDTO, Shipment>> shipments = ConcurrentCollectionUtil
			.parMapWithCallBacks(Lists.newArrayList(profileMap.entrySet()), getShipment,
					refundShipment, executor);

		logger.info("Batch shipping label purchase done: {}", shipments);

		Map<ShipmentDTO, ShipEvent> eventMap = Maps.newHashMap();
		for (Entry<ShipmentDTO, Shipment> shipEntry : shipments) {
			logger.info("Preparing shipment entry {} to add to batch buy shipping label event map", shipEntry);

			ShipmentDTO dto = shipEntry.getKey();
			Shipment shipment = shipEntry.getValue();
			Rate rate = shipment.getSelectedRate();
			ShipEvent event = new ShipEvent(null, new Date(), dto.getShipmentId(),
				shipment.getId(), MoneyUtils.parseCentsFromDollars(rate.getRate().toString()),
				"Printed Shipping Label", dto.getShipmentId() + CREATION_EVENT_SUFFIX,
				ServiceProvider.EASYPOST, shipment.getTrackingCode(), null, null);
			PackageInfo info = new PackageInfo(rate.getServiceCode(), shipment.getTrackingCode(),
				new Date());
			info.setLabelUrl(shipment.getPostageLabel().getLabelUrl());
			event.setInfo(info);
			eventMap.put(dto, event);
		}
		return eventMap;
	}

	@Override
	public void refundShipment(String shipmentId) throws EasyPostException{
		Shipment.retrieve(shipmentId).refund();
	}


	@Override
	public ServiceProvider getServiceProvider() {
		return ServiceProvider.EASYPOST;
	}

	/**
	 * EasyPost only returns one suggested address but other solutions return multiple.
	 * Creating a signature that works with both the UI and future iterations.
	 *
	 * @param address - the address to check
	 * @return null if the address is verified, a suggested MailAddress if not.
	 */
	@Override
	public List<MailAddress> verifyAddress(MailAddress address) {
		List<MailAddress> ret = Lists.newArrayList();
		AddressDTO toVerify = new AddressDTO(address);
		toVerify.setCountry("US"); //EasyPost needs "US" instead of United States
		try {
			boolean dirty = false;

			//Easy Post is case-sensitive, so we're making sure that this is actually a new address. Also,
			//there are fields we don't want corrected - e.g. country
			Address verified = Address.create(getAddress(toVerify)).verify();

			//Check street1, street2, state, and zip
			if(address.getStreet1() != null &&
				!address.getStreet1().equalsIgnoreCase(verified.getStreet1())) {
				address.setStreet1(verified.getStreet1());
				dirty = true;
			}
			if(address.getStreet2() != null &&
				!address.getStreet2().equalsIgnoreCase(verified.getStreet2())) {
				address.setStreet2(verified.getStreet2());
				dirty = true;
			}
			if(address.getState() != null &&
				!address.getState().equalsIgnoreCase(verified.getState())) {
				address.setStreet1(verified.getStreet1());
				dirty = true;
			}
			if(address.getZip() != null &&
				!address.getZip().equalsIgnoreCase(verified.getZip())) {
				address.setZip(verified.getZip());
				dirty = true;
			}
			if(dirty) ret.add(address);

		} catch(EasyPostException epe) {
			//We should never throw an EasyPostException.
			logger.info("EasyPost Address Verification Error", epe);
		}
		return ret;
	}

	private MailAddress convertEasyPostAddress(Address easyPostAddress) {
		if(easyPostAddress == null) {
			return null;
		}
		logger.info("EasyPost address  {}", easyPostAddress);
		MailAddress mailAddress = new MailAddress();
		mailAddress.setStreet1(easyPostAddress.getStreet1());
		mailAddress.setStreet2(easyPostAddress.getStreet2());
		mailAddress.setCity(easyPostAddress.getCity());
		mailAddress.setState(easyPostAddress.getState());
		mailAddress.setZip(easyPostAddress.getZip());
		mailAddress.setCountry(easyPostAddress.getCountry());
		return mailAddress;
	}

	private Map<String, Object> getEasyPostAddressRequestParameters(MailAddress mailAddress) {
		ImmutableMap.Builder<String, Object> addressMapBuilder = new ImmutableMap.Builder<>();
		logger.info("MailAddress  {}", mailAddress);
		if (mailAddress.getStreet1() != null){
			addressMapBuilder.put("street1", mailAddress.getStreet1());
		}
		if (mailAddress.getStreet2() != null){
			addressMapBuilder.put("street2", mailAddress.getStreet2());
		}
		if (mailAddress.getCity() != null){
			addressMapBuilder.put("city", mailAddress.getCity());
		}
		if (mailAddress.getState() != null){
			addressMapBuilder.put("state", mailAddress.getState());
		}
		if (mailAddress.getZip() != null) {
			addressMapBuilder.put("zip", mailAddress.getZip());
		}
		if (mailAddress.getCountry() != null) {
			addressMapBuilder.put("country", mailAddress.getCountry());
		}
		return addressMapBuilder.build();
	}

	/**
	 * EasyPost returns one suggested address but other solutions return multiple.
	 * Creating a signature that works with both the UI and future iterations.
	 *
	 * @param addressToVerify - the address to check
	 * @return empty HashMap if the address is not shippable, otherwise a map contains between carrier and address.
	 */
	@Override
	public Map<ShippingCarrier, MailAddress> getAddressSuggestions(MailAddress addressToVerify) throws AddressVerificationException {
		checkArgument(addressToVerify != null);
		Map<String, Object> addressParams = getEasyPostAddressRequestParameters (addressToVerify);

		logger.info("MailAddress  {}", addressToVerify);

		List<Pair<ShippingCarrier, MailAddress>> addressSuggestions = ConcurrentCollectionUtil.simpleParMap(
			ImmutableList.of(ShippingCarrier.USPS, ShippingCarrier.FEDEX), (ShippingCarrier carrier) -> {
				return Pair.of(carrier, convertEasyPostAddress(addressVerificationFunction.apply(addressParams, carrier.getCarrierName()).orElse(null)));
			}
			, executor);

		return addressSuggestions.stream()
			.filter(carrierMailAddressPair -> carrierMailAddressPair.getRight() != null)
			.collect(Collectors.toMap(
					Pair::getKey,
					Pair::getValue));
	}

	@Override
	public Tracker createTracker(String trackingCode, String carrier) throws EasyPostException {
		Preconditions.checkArgument(trackingCode != null, "Cannot create tracker without trackingCode");
		Map<String, Object> params = new HashMap<>();
		params.put("tracking_code", trackingCode);
		params.put("carrier", carrier);
		return Tracker.create(params);
	}

	@Override
	@Transactional
	public void updateTrackerStatus(Long trackingStatusId, TrackingStatus.Status packageStatus) {
		Preconditions.checkArgument(trackingStatusId != null, "Cannot update TrackerStatus without trackingStatusId");
		Preconditions.checkArgument(packageStatus != null, "Cannot update TrackerStatus without trackerStatus");
		TrackingStatus trackingStatus = trackingStatusDao.findById(trackingStatusId);

		trackingStatus.setStatus(packageStatus);
		trackingStatusDao.update(trackingStatus);
	}

	@Override
	public TrackingStatusDTO translateTrackingStatus(String statusString) {
		Preconditions.checkArgument(statusString != null, "Cannot translate TrackingStatus without statusString");
		Tracker tracker = translateEventStrToTracker(statusString);
		return new TrackingStatusDTO.Mapper().withHistory().mapFrom(tracker);
	}

	private Tracker translateEventStrToTracker(String trackingStatusEventStr) {
		Preconditions.checkArgument(trackingStatusEventStr != null, "Cannot translate TrackingStatus without trackingStatus Event String");
		Gson gson = new GsonBuilder().registerTypeAdapter(Event.class, new EventDeserializer()).create();
		Event trackingStatusEvent = gson.fromJson(trackingStatusEventStr, Event.class);
		Tracker tracker = (Tracker) trackingStatusEvent.getResult();
		return tracker;
	}
}
