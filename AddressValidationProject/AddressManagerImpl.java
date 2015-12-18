package sneakpeeq.core.managers.impl;

import sneakpeeq.core.daos.ZipcodeDataDao;
import sneakpeeq.core.entity.AddressVerificationDTO;
import sneakpeeq.core.entity.shared.AddressDTO;
import sneakpeeq.core.entity.shared.enums.ShippingCarrier;
import sneakpeeq.core.exceptions.InvalidAddressException;
import sneakpeeq.core.exceptions.address.MissingPhoneNumberException;
import sneakpeeq.core.managers.AddressManager;
import sneakpeeq.core.managers.TransactionManager;
import sneakpeeq.core.models.MailAddress;
import sneakpeeq.core.models.ZipcodeData;
import sneakpeeq.core.service.EasyPostService;
import sneakpeeq.core.util.AddressUtils;
import sneakpeeq.core.util.UnitConversionUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

@Service
public class AddressManagerImpl implements AddressManager {
	Logger logger = LoggerFactory.getLogger(AddressManager.class);

	// Note that, this could match "PO BOX Street 123", but we currently don't have a way to differentiate this
	// from "PO BOX A123". So we decide to over-detect PO BOX, instead of under-detect
	// By over-detecting PO BOX, we may send some order with USPS. By under-detecting it, order will get stuck.
	// "P(.)O(.) BOX" + anything, case insensitive
	private static final Pattern REGEX_PO_BOX_STREET = Pattern
			.compile("(\\b)(p\\.?)(\\s*)(o\\.?)(\\s*)(box).*", Pattern.CASE_INSENSITIVE);

	// Matches space + "BOX" + anything, case insensitive
	private static final Pattern REGEX_BOX_STREET = Pattern
			.compile("(\\s*)(box).*", Pattern.CASE_INSENSITIVE);

	private final ZipcodeDataDao zipcodeDataDao;
	private final TransactionManager txManager;
	private final EasyPostService easyPostService;

	@Autowired
	public AddressManagerImpl(ZipcodeDataDao zipcodeDataDao, TransactionManager txManager,
	                          EasyPostService easyPostService) {
		this.zipcodeDataDao = zipcodeDataDao;
		this.txManager = txManager;
		this.easyPostService = easyPostService;
	}

	private Map<String,ZipcodeData> cachedZipcodeDataMap;

	@PostConstruct
	public void initializeData() {
		cachedZipcodeDataMap =  ImmutableMap.copyOf(
			txManager.inItsOwnTxWithResult(zipcodeDataDao::findAll).stream()
				.collect(Collectors.toMap(ZipcodeData::getZipcode, Function.identity()))
		);
	}

	@Override
	public boolean isMilitaryAddress(AddressDTO address) {
		boolean isMillitaryAddress = address != null && address.getCountry() != null &&
				 address.getCity() != null && address.isUnitedStates() && (
				address.getCity().equalsIgnoreCase("APO") ||
				address.getCity().equalsIgnoreCase("DPO") ||
				address.getCity().equalsIgnoreCase("FPO"));
		if(isMillitaryAddress) {
			logger.info("Address {} is detected as military address", address);
		} else {
			logger.info("Address {} is detected as non military address", address);
		}
		return isMillitaryAddress;
	}

	/**
	 * Two scenarios can be parsed as PO BOX
	 * 1. one of address1/2 matches "PO BOX"
	 * 2. one of address1/2 matches "BOX", the other is empty
	 * */
	@Override
	public boolean isPOBox(AddressDTO address) {
		if (address == null) return false;
		// one of address1/2 matches "PO BOX"
		boolean isPOBox = false;
		// one of address1/2 matches "PO BOX"
		if(streetMatchesPOBox(address.getStreet1()) || streetMatchesPOBox(address.getStreet2())) {
			isPOBox = true;
		}
		// one of address1/2 matches "BOX", the other one is null/empty
		if(streetMatchesBox(address.getStreet1()) && isNullOrEmpty(address.getStreet2())) {
			isPOBox = true;
		}
		if(streetMatchesBox(address.getStreet2()) && isNullOrEmpty(address.getStreet1())) {
			isPOBox = true;
		}
		if(isPOBox) {
			logger.info("Address {} is detected as PO BOX", address);
		} else {
			logger.info("Address {} is detected as non-PO BOX", address);
		}
		return isPOBox;
	}

	private boolean streetMatchesPOBox(String streetAddress) {
		return streetAddress != null && REGEX_PO_BOX_STREET.matcher(streetAddress).matches();
	}
	private boolean streetMatchesBox(String streetAddress) {
		return streetAddress != null && REGEX_BOX_STREET.matcher(streetAddress).matches();
	}

	@Override
	public boolean isCountryValid(AddressDTO address) {
		Map<String,String>  map = AddressDTO.COUNTRIES;
		if (address.getCountry() == null) {
			return false;
		}
		return map.containsKey(address.getCountry()) || map.containsValue(address.getCountry().toUpperCase());
	}

	@Override
	public boolean isAddressValid(AddressDTO address) {
		return isAddressValid(address, false);
	}

	private boolean isAddressValid(AddressDTO address, boolean shouldValidatePhone) throws InvalidAddressException {

		if(address == null) {
			return false;
		}

		if(!isCountryValid(address)) {
			logger.info("country invalid for {}", address);
			return false;
		}

		if (address.isUnitedStates()) {
			if(address.getZip() == null) {
				logger.info("no zip code for {}", address);
				return false;
			} else if(isMilitaryAddress(address)) {
				return true; // No validation possible for these
			} else {
				logger.info("checking zip code for {}", address);
				ZipcodeData data = getZipcodeDataFromZipcode(address.getZip().trim());
				return data != null && data.getCity().equalsIgnoreCase(address.getCity()) && data.getState()
						.equalsIgnoreCase(address.getState());
			}
		} else {
			logger.info("international address  {}", address);
			if (shouldValidatePhone && !isPhoneValid(address.getPhone())) {
				throw new MissingPhoneNumberException("Please input a phone number");
			}
			return true;
		}
	}

	@Override
	public AddressVerificationDTO verifyAddress(MailAddress address) {
		checkArgument(address != null);

		AddressVerificationDTO addressSuggestion = new AddressVerificationDTO();

		Map<ShippingCarrier, MailAddress> addressSuggestionEntry = easyPostService.getAddressSuggestions(address);

		for (Map.Entry<ShippingCarrier, MailAddress> entry : addressSuggestionEntry.entrySet()) {
			/*
			 * If the address is shippable by the carrier, add it to the list of supported carrier
			 * The suggested address will be one of the suggested addresses. If USPS suggestion is present,
			 * it'll be used on top of other addresses
			 */

			addressSuggestion.getVerifiedCarrier().add((entry.getKey()));

			if (entry.getKey() == ShippingCarrier.USPS || addressSuggestion.getSuggestedAddress() == null) {
				addressSuggestion.setSuggestedAddress(entry.getValue());
			}
		}
		return addressSuggestion;
	}

	/*
	 * phone validation for international
	 * the bare minimum is to have something...
	 */
	private boolean isPhoneValid(String phone) {
		return !Strings.isNullOrEmpty(phone);
	}

	@Override
	@Cacheable("shipping")
	public ZipcodeData getZipcodeDataFromZipcode(String zipcode) {
		String zipcodeWithLeadingZerosRemoved = zipcode.replaceFirst("^0*", "");
		return cachedZipcodeDataMap.containsKey(zipcodeWithLeadingZerosRemoved)
				? cachedZipcodeDataMap.get(zipcodeWithLeadingZerosRemoved)
				: null;
	}



	public BigDecimal getDistanceInKm(AddressDTO from, AddressDTO to)  {

		if(from.getZip() == null || to.getZip() == null) {
			return null;
		}

		ZipcodeData fromZip = getZipcodeDataFromZipcode(from.getZip());
		ZipcodeData toZip = getZipcodeDataFromZipcode(to.getZip());
		if(toZip != null && fromZip != null && toZip.getLat() != null && fromZip.getLat() != null) { //validate
			return new BigDecimal(AddressUtils.distanceInKm(toZip.getLat(), toZip.getLng(), fromZip.getLat(), fromZip.getLng()));
		}

		return null; // not enough info available
	}

	public BigDecimal getDistanceInMi(AddressDTO from, AddressDTO to) throws InvalidAddressException {
		return UnitConversionUtils.kmToMiles(getDistanceInKm(from,to));
	}

	public String getCountryCode(String countryName) {
		if (countryName == null) {
			return null;
		}

		// Check if contains value because countryName may already be a country code
		return AddressDTO.COUNTRIES.containsValue(countryName)
				? countryName
				: Strings.nullToEmpty(AddressDTO.COUNTRIES.get(countryName));
	}

	@Override
	public void validateAddress(AddressDTO address, boolean shouldValidatePhone) throws InvalidAddressException {
		validateCountry(address);

		try {
			if (!isAddressValid(address, shouldValidatePhone)) {
				logger.info("address check failed for {}", address);
			}
		} catch (InvalidAddressException e) {
			throw e;
		}
	}

	@Override
	public void validateCountry(AddressDTO address) {
		if (!isCountryValid(address)) {
			throw new InvalidAddressException("The country you entered is invalid, please try again.");
		}
	}
}
