package sneakpeeq.core.managers.FAS;

import sneakpeeq.core.FulfillmentClient.FulfillmentClient;
import sneakpeeq.core.FulfillmentClient.FulfillmentClientFactory;
import sneakpeeq.core.annotations.DatabaseGroup;
import sneakpeeq.core.annotations.LogContext;
import sneakpeeq.core.cache.FulfillmentBlockedOrderCache;
import sneakpeeq.core.daos.FAS.FulfillmentOrderDao;
import sneakpeeq.core.daos.FAS.ShipmentDao;
import sneakpeeq.core.daos.FAS.ShipmentItemDao;
import sneakpeeq.core.daos.OrderDao;
import sneakpeeq.core.entity.FAS.enums.FulfillmentOrderStatus;
import sneakpeeq.core.entity.FAS.enums.ShipmentStatus;
import sneakpeeq.core.entity.OrderLineItemDTO;
import sneakpeeq.core.entity.RateShoppingRecordDTO;
import sneakpeeq.core.entity.WPISnapshot;
import sneakpeeq.core.entity.shared.AddressDTO;
import sneakpeeq.core.entity.shared.enums.OrderItemStatus;
import sneakpeeq.core.entity.shared.enums.ServiceLevel;
import sneakpeeq.core.entity.shared.enums.ShippingCarrier;
import sneakpeeq.core.exceptions.FAS.FailedShipmentUpdateException;
import sneakpeeq.core.exceptions.FAS.ShipmentNotInWarehouseException;
import sneakpeeq.core.exceptions.FAS.WarehouseException;
import sneakpeeq.core.exceptions.IllegalUpdateException;
import sneakpeeq.core.exceptions.InvalidAddressException;
import sneakpeeq.core.exceptions.ItemUnavailableException;
import sneakpeeq.core.exceptions.OrderStatusException;
import sneakpeeq.core.exceptions.UnavailableRateException;
import sneakpeeq.core.exceptions.UnavailableWeightException;
import sneakpeeq.core.managers.AddressManager;
import sneakpeeq.core.managers.FaultInjector;
import sneakpeeq.core.managers.OrderInventoryDetailManager;
import sneakpeeq.core.managers.OrganizationManager;
import sneakpeeq.core.managers.PackageTrackingManager;
import sneakpeeq.core.managers.ProductManager;
import sneakpeeq.core.managers.RateShoppingRecordManager;
import sneakpeeq.core.managers.SiteManager;
import sneakpeeq.core.managers.TransactionManager;
import sneakpeeq.core.managers.VictoryManager;
import sneakpeeq.core.managers.fulfillment.FulfillmentOrderManager;
import sneakpeeq.core.managers.fulfillment.FulfillmentRateManager;
import sneakpeeq.core.models.FAS.FulfillmentItem;
import sneakpeeq.core.models.FAS.FulfillmentOrder;
import sneakpeeq.core.models.FAS.FulfillmentShipment;
import sneakpeeq.core.models.FAS.Package;
import sneakpeeq.core.models.FAS.PackageDTO;
import sneakpeeq.core.models.FAS.WarehouseInventoryReservationLog;
import sneakpeeq.core.models.MailAddress;
import sneakpeeq.core.models.Model;
import sneakpeeq.core.models.Order;
import sneakpeeq.core.models.Organization;
import sneakpeeq.core.models.Product;
import sneakpeeq.core.models.ProductCluster;
import sneakpeeq.core.models.ProductInventoryLog;
import sneakpeeq.core.models.ShipmentRateShopping;
import sneakpeeq.core.models.ShippingMethod;
import sneakpeeq.core.models.VictoryLineItem;
import sneakpeeq.core.models.event.EBShipmentUpdateEvent;
import sneakpeeq.core.models.fulfillment.ServiceProvider;
import sneakpeeq.core.models.fulfillment.ShippingParcel;
import sneakpeeq.core.models.fulfillment.ShippingProfile;
import sneakpeeq.core.models.plugins.SiteFulfillmentDelayPlugin;
import sneakpeeq.core.pojos.PackageInfo;
import sneakpeeq.core.pojos.ShipmentUpdate;
import sneakpeeq.core.pojos.ShippingRateOptional;
import sneakpeeq.core.util.FAS.ShipmentDurationCalculator;
import sneakpeeq.core.util.Ids;
import sneakpeeq.core.util.IntegerMap;
import sneakpeeq.core.util.OrderUtils;
import sneakpeeq.core.util.ShipmentUtils;
import sneakpeeq.core.util.TransactionPaginator;
import sneakpeeq.modules.charge.ChargeComponent;
import sneakpeeq.modules.charge.utils.ChargeEventExternalEntityType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.eventbus.EventBus;
import org.hibernate.exception.LockAcquisitionException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.symphonycommerce.inventory.exceptions.FailedToRestorePoolInventoryException;
import com.symphonycommerce.inventory.exceptions.InventoryLineItemException;
import com.symphonycommerce.inventory.exceptions.UnknownPoolInventoryException;
import com.symphonycommerce.inventory.managers.SkuInventoryManager;
import com.symphonycommerce.inventory.pojos.RequestLineItem;
import com.symphonycommerce.lib.exceptions.SPException;
import com.symphonycommerce.lib.metrics.Metrics;
import com.symphonycommerce.lib.metrics.SignalFuseService;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Collections.EMPTY_SET;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static sneakpeeq.core.annotations.DataAccessContext.READ_ONLY;
import static sneakpeeq.core.annotations.LogContext.LogContextField.FULFILLMENT_ORDER_ID;
import static sneakpeeq.core.annotations.LogContext.LogContextField.ORDER_ID;
import static sneakpeeq.core.annotations.LogContext.LogContextField.SHIPMENT_ID;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.BAD_SHIPPING_METHOD;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.CANCELLED;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.CREATED;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.DROPSHIP_SHIPPING;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.FAILED_PAYMENT;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.INVENTORY_NA;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.SHIPPED;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.UNKNOWN_ERROR_HOLD;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.VOIDED;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.WAREHOUSE;
import static sneakpeeq.core.entity.FAS.enums.ShipmentStatus.WAREHOUSE_HOLD;
import static sneakpeeq.core.util.NinjaAlertUtil.SPNinja.FULFILLMENT_OPS;
import static sneakpeeq.core.util.NinjaAlertUtil.alertNinja;

@Service
public class ShipmentManagerImpl implements ShipmentManager {

	private final Logger logger = LoggerFactory.getLogger(ShipmentManagerImpl.class);

	@Autowired private ShipmentDao shipmentDao;
	@Autowired private FulfillmentOrderDao fulfillmentOrderDao;
	@Autowired private PackageManager packageManager;
	@Autowired private ShipmentItemDao shipmentItemDao;
	@Autowired private OrderDao orderDao;
	@Autowired private ProductManager productManager;
	@Autowired private FulfillmentOrderManager fulfillmentOrderManager;
	@Autowired private FulfillmentClientFactory fulfillmentClientFactory;
	@Autowired private ShipMethodManager shipMethodManager;
	@Autowired private FulfillmentRateManager fulfillmentRateManager;
	@Autowired private FulfillmentBlockedOrderCache blockedOrderCache;
	@Autowired private AddressManager addressManager;
	@Autowired private ShipmentCreator shipmentCreator;
	@Autowired private WarehouseInventoryManager warehouseInventoryManager;
	@Autowired private WarehouseInventoryAuditManager warehouseInventoryAuditManager;
	@Autowired private ChannelInventoryAuditManager channelInventoryAuditManager;
	@Autowired private RateShoppingRecordManager rateShoppingRecordManager;
	@Autowired private SignalFuseService signalFuseService;
	@Autowired private FaultInjector faultInjector;
	@Autowired private OrganizationManager organizationManager;
	@Autowired private ShipmentDurationCalculator shipmentDurationCalculator;
	@Autowired private VictoryManager victoryManager;
	@Autowired private SiteManager siteManager;
	@Autowired private SkuInventoryManager skuInventoryManager;
	@Autowired @Lazy private ChargeComponent chargeComponent;
	@Autowired private OrderInventoryDetailManager orderInventoryDetailManager;
	@Autowired private EventBus eventBus;
	@Autowired private TransactionPaginator txPaginator;
	@Autowired private WPISnapshotFactory wpiSnapshotFactory;
	@Autowired private PackageTrackingManager packageTrackingManager;
	@Autowired private TransactionManager transactionManager;


	@Override
	@Transactional(readOnly = true)
	public List<FulfillmentShipment> readShipments(Collection<Long> shipmentIds) {
		return shipmentDao.findByIds(shipmentIds);
	}

	@Override
	@Transactional
	public void createFulfillmentOrder(@LogContext(ORDER_ID) Order order) {
		logger.info("Creating FulfillmentOrder for order {}", order.getId());
		FulfillmentOrder fulfillmentOrder = new FulfillmentOrder();
		fulfillmentOrder.setOrderId(order.getId().toString());
		fulfillmentOrder.setStatus(FulfillmentOrderStatus.PENDING);
		fulfillmentOrder.setOrg(order.getVictory().getSubdomain());
		orderDao.saveOrUpdate(order);
		fulfillmentOrder.setOriginalOrderCreationTime(order.getCreated());
		SiteFulfillmentDelayPlugin fulfillmentDelayPlugin = victoryManager.findSite(order.getVictory())
				.getSiteFulfillmentDelayPlugin();
		fulfillmentOrder.setFulfillmentDelayInMinutes(
				OrderUtils.isEdiOrder(order) ? 0 : fulfillmentDelayPlugin.getFulfillmentDelayInMinutes());
		fulfillmentOrderDao.save(fulfillmentOrder);
		signalFuseService.counter(Metrics.CounterMetricType.FULFILLMENT_ORDERS_PENDING).inc();
	}

	@Override
	@Transactional(readOnly = true)
	public FulfillmentOrder findById(Long id) {
		return fulfillmentOrderDao.findById(id);
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public Set<String> findOrgsWithUnfilledOrders() {
		return fulfillmentOrderDao.findOrgsWithUnfilledOrders();
	}

	@Override
	@Transactional
	public FulfillmentShipment findByTrackingNumber(String trackingNumber) {
		return shipmentDao.findByTrackingNumber(trackingNumber);
	}

	@Override
	@Transactional(readOnly=true)
	public FulfillmentOrder getFulfillmentOrderBySymphonyOrderId(@LogContext(ORDER_ID) Long orderId){
		return fulfillmentOrderDao.findBySymphonyOrderId(orderId.toString());
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public IntegerMap<Product> getQuantityMap(@LogContext(SHIPMENT_ID) Long shipmentId) {
		FulfillmentShipment shipment = readShipment(shipmentId);
		return shipment == null ? IntegerMap.create() : shipment.getQuantities();
	}

	@Override
	@Transactional(readOnly = true)
	public FulfillmentShipment readShipment(@LogContext(SHIPMENT_ID) Long shipmentId) {
		List<FulfillmentShipment> shipments = readShipments(Lists.newArrayList(shipmentId));
		return shipments.isEmpty() ? null : Iterables.getOnlyElement(shipments);
	}

	@Override
	@Transactional
	public FulfillmentShipment getShipmentForUpdate(@LogContext(SHIPMENT_ID) Long shipmentId) {
		try {
			return shipmentDao.getShipmentWithLock(shipmentId);
		} catch (LockAcquisitionException e) {
			logger.error(format("Dead lock detected for getting shipment %s", shipmentId), e);
			throw e;
		}
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public List<Long> getShipmentIdsByLineItemIds(List<Long> vliIds) {
		return shipmentDao.findShipmentIdsByVictoryLineItemIds(vliIds);
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public Map<Long, List<Long>> getShipmentIdsMapByLineItemIds(List<Long> vliIds) {
		Map<Long, List<Long>> map = new HashMap<>();
		vliIds.stream().forEach(id -> map.put(
				id, getShipmentIdsByLineItemIds(Lists.newArrayList(id))));
		return map;
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public Collection<FulfillmentShipment> getShipmentsByOrderId(@LogContext(ORDER_ID) String orderId) {
		List<String> orderIds = Lists.newArrayList(orderId.toString());
		return this.getShipmentsByOrderIds(orderIds).get(orderId);
	}

	/**
	 * Get Multimap from OrderId to FulfillmentShipment
	 * Note that one OrderId may map to multiple Shipments
	 * */
	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public Multimap<String, FulfillmentShipment> getShipmentsByOrderIds(List<String> orderIds) {
		return shipmentDao.findShipmentsByOrderId(orderIds);
	}

	/**
	 * Get Multimap from OrderId to FulfillmentShipment
	 * Note that one OrderId may map to multiple Shipments
	 * */
	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public List<Long> getShipmentIdsByOrderId(@LogContext(SHIPMENT_ID) String orderId) {
		return shipmentDao.findShipmentIdsByOrderId(orderId);
	}

	/**
	 * Get Shipment Status by Id
	 * */
	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public ShipmentStatus getShipmentStatusByShipmentId(@LogContext(SHIPMENT_ID)Long shipmentId) {
		return readShipment(shipmentId).getStatus();
	}

	@Override
	@Transactional
	public Map<Long,Optional<ShipmentUpdate>> recoverShipmentStatusIfNecessary(@LogContext(FULFILLMENT_ORDER_ID) Long fulfillmentOrderId) {
		logger.info("preparing to recover any indeterminate shipments for fulfillmentOrderId = {}", fulfillmentOrderId);
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findById(fulfillmentOrderId);
		checkNotNull(fulfillmentOrder);
		Map<Long,Optional<ShipmentUpdate>> updatesByShipmentId = new HashMap<>();
		if(fulfillmentOrder.getStatus() == FulfillmentOrderStatus.PENDING){
			List<Long> shipmentIds = getShipmentIdsByOrderId(fulfillmentOrder.getOrderId());
			logger.info("recovering indeterminate shipments num={}", shipmentIds.size());
			for (Long shipmentId : shipmentIds) {
				FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
				if (shipment.getStatus() == CREATED) {
					updatesByShipmentId.put(shipmentId, Optional.empty());

					logger.info("recovering shipment for orderId = {}: shipmentId={}", fulfillmentOrder.getOrderId(), shipmentId);
					try {
						ShipmentUpdate shipmentUpdate = updateStatusFromWarehouse(shipment.getId());
						updatesByShipmentId.put(shipmentId, Optional.ofNullable(shipmentUpdate));
						//With the existing transaction structure, we deduct inventory in a separate transaction (T2)
						//after creating a shipment in a transaction (T1) of its own. A failed shipment exists
						//because T1 succeeded and T2 failed. We set the isInWarehouse flag to true and update
						//inventory in T2. We should enforce the invariants that hold true post T1 and pre T2.
						if(shipment.isInWarehouse()){
							//isInWarehouse flag is false pre T2 and is set only in T2. This is illegal.
							throw new IllegalUpdateException(
									format("Failed shipment %s already marked to be in warehouse", shipment.getId()));
						}

						shipment.setInWarehouse(true);
						shipment.setInWarehouseAt(new Date());
						updateInventoryPostPushToWarehouse(shipment);
						shipmentDao.update(shipment);
					} catch (ShipmentNotInWarehouseException sniwe) {
						// not in warehouse && created =>
						logger.info("shipment id={} not in warehouse, voiding ", shipmentId);
						voidShipment(shipment);
					} catch (WarehouseException e) {
						shipment.setStatus(UNKNOWN_ERROR_HOLD);
						shipmentDao.update(shipment);
						logger.info("shipment id={}-{} warehouse exception {} setting shipment status to {}",
								fulfillmentOrder.getOrderId(), shipmentId, e.getMessage(), shipment.getStatus());
					} finally {
						fireShipmentUpdateEvent(shipment);
					}
				} else {
					logger.info("Not recovering shipment because it is has status {}", shipment.getStatus());
				}
			}
		} else {
			logger.info("no shipments to void fulfilmentOrder {} status={},", fulfillmentOrderId, fulfillmentOrder.getStatus());
		}
		logger.debug("found {} shipment updates", updatesByShipmentId.size());
		return updatesByShipmentId;

	}

	@Override
	@Transactional
	public Collection<FulfillmentShipment> sendToWarehouse(Collection<Long> shipmentIds, Long fulfillmentOrderId) {
		Collection<FulfillmentShipment> readyShipments = shipmentDao.findByIds(shipmentIds);
		List<Long> foundIds = readyShipments.stream().map(Model::getId).collect(toList());
		logger.info("found {} ready shipments: {}", readyShipments.size(), foundIds);
		for(FulfillmentShipment shipment : readyShipments){
			boolean isShipmentSentToWarehouse = true;
			try{
				pushShipmentToWarehouse(shipment);
			} catch (Exception e) {
				//Its important that we catch an exception here so that the transaction can complete successfully
				//and save the shipments that have been successfully sent to the warehouse.
				//XXX: However a generic exception catch like this is dangerous, this allows the transaction to
				//succeed even if we throw ItemUnavailableException which in turn leads to inventory being deducted
				//from WarehouseInventory but not Product.inventory.

				String message = format("Exception occurred while pushing shipment %d to the warehouse", shipment.getId());
				logger.error(message, e);
				//TODO: Catch orders/shipments that are failing repeatedly
				isShipmentSentToWarehouse = false;
			} finally {
				if (isShipmentSentToWarehouse) {
					fireShipmentUpdateEvent(shipment);
				}
			}
		}
		adjustFulfillmentOrderStatus(fulfillmentOrderId);
		faultInjector.proceed(TransactionInterceptor.currentTransactionStatus());
		return readyShipments;
	}
	/**
	 * Hold shipment by ship
	 * mentIds. Each shipment will be held in its own transaction.
	 * */
	@Override
	@Transactional
	public void holdShipmentByShipmentId(@LogContext(SHIPMENT_ID) Long shipmentId) {
		logger.info("start to hold shipment {}", shipmentId);
		FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
		holdAndAbortIfFailedShipment(shipment);

		if(!shipment.getStatus().canBePutOnHold()) {
			String illegalUpdateError = String.format("Shipment %s cannot be put on hold, because it currently has status %s", shipmentId, shipment.getStatus());
			logger.warn(illegalUpdateError);
			throw new IllegalUpdateException(illegalUpdateError);
		}

		if(shipment.getStatus() == WAREHOUSE) {
			shipment.setStatus(WAREHOUSE_HOLD);
		}else{
			throw new SPException(format("Shipment %s has status %s, it cannot be put on hold", shipmentId, shipment.getStatus()));
		}

		if(shipment.isInWarehouse()) {
			getFulfillmentClient(shipment).holdShipment(shipment);
		}

		shipmentDao.saveOrUpdate(shipment);
		fireShipmentUpdateEvent(shipment);
		logger.info("finish holding shipment {}", shipmentId);
	}

	/**
	 * Hold shipment by orderIds. Each order will be held in its own transaction.
	 * If there are multiple shipments associated with same orderId, then hold them all.
	 * Each shipment will be held in its own transaction, regardless of other shipments in the same order.
	 * */
	@Override
	@Transactional
	public void holdShipmentByOrderId(@LogContext(ORDER_ID) String orderId) {
		List<Long> shipmentIds = getShipmentIdsByOrderId(orderId);
		for(Long shipmentId : shipmentIds) {
			FulfillmentShipment shipment = readShipment(shipmentId);
			if(shipment.getStatus().canBePutOnHold()) {
				holdShipmentByShipmentId(shipmentId);
			}
		}
	}

	/**
	 * Release shipment by shipmentIds. Each shipment will be Released in its own transaction.
	 * */
	@Override
	@Transactional
	public ShipmentUpdate releaseShipmentByShipmentId(@LogContext(SHIPMENT_ID) Long shipmentId) {
		ShipmentUpdate shipmentUpdate = null;
		FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
		holdAndAbortIfFailedShipment(shipment);
		logger.info("Releasing shipment {} that has status {}", shipmentId, shipment.getStatus());
		if(!shipment.getStatus().canBeReleased()) {
			String illegalUpdateError = String.format("Shipment %s cannot be released, because it currently has status %s", shipmentId, shipment.getStatus());
			logger.warn(illegalUpdateError);
			throw new IllegalUpdateException(illegalUpdateError);
		}

		if(shipment.isInWarehouse()) {
			logger.info("Releasing shipment {} in warehouse", shipment.getId());
			shipmentUpdate = getFulfillmentClient(shipment).releaseShipment(shipment);
			shipment.setStatus(WAREHOUSE);
		} else {
			// if not in warehouse, shipment should be voided
			voidShipment(shipment);
			shipmentUpdate = new ShipmentUpdate();
			shipmentUpdate.setShipmentStatus(VOIDED);
		}
		shipmentDao.saveOrUpdate(shipment);
		fireShipmentUpdateEvent(shipment);
		return shipmentUpdate;
	}

	/**
	 * Release shipment by orderIds. Each order will be Released in its own transaction.
	 * If there are multiple shipments associated with same orderId, then Release them all.
	 * Each shipment will be Released in its own transaction, regardless of other shipments in the same order.
	 * */
	@Override
	@Transactional
	public void releaseShipmentByOrderId(@LogContext(ORDER_ID) String orderId) {
		List<Long> shipmentIds = getShipmentIdsByOrderId(orderId);
		for(Long shipmentId : shipmentIds) {
			FulfillmentShipment shipment = readShipment(shipmentId);
			if(shipment.getStatus().canBeReleased()) {
				releaseShipmentByShipmentId(shipmentId);
			}
		}
	}

 	@Override
    // Do not use transaction here. This may throw a runtime exception and mark the outer TX method as rollback-only,
    // although the outer method can catch and ignore the exception.
	public void pushShipmentToWarehouse(@LogContext(SHIPMENT_ID) FulfillmentShipment shipment) {
		logger.info("Sending shipment {} to warehouse", shipment.getId());
		if(shipment.getWarehouse().isDropship()){
			String message = format("Shipment %s is supposed to be drop-shipped", shipment.getId());
			throw new WarehouseException(message);
		}
		if (!shipment.getStatus().canBePushedToWarehouse()) {
			throw new WarehouseException(format("Shipment %s can not be sent to the warehouse", shipment));
		}
		if (shipment.isInWarehouse()){
			throw new WarehouseException(format("Shipment %s is already in warehouse", shipment));
		}

	    if (!authorizeShipmentCharge(shipment)) {
		    return;
	    }

	    try{
			overwriteShippingMethodsForPoBoxOrMilitaryAddress(shipment);
			List<ShippingMethod> shippingMethods = getShippingMethods(shipment) ;

			if(shippingMethods.isEmpty()) {
				logger.info("Not pushing shipment {} because it doesn't have any shipping method", shipment.getId());
				return;
			}

			if(shippingMethods.size() == 1) {
				ShippingMethod shippingMethod = Iterables.getOnlyElement(shippingMethods);
				logger.info("Rate Shopping Service has chosen {} for shipment {}", shippingMethod.getName(), shipment.getId());
				shipment.setChosenShippingMethod(shippingMethod);
			}

			getFulfillmentClient(shipment).pushAndUpdateShipment(shipment, shippingMethods);

			// If order fails with BAD_SHIPPING_METHOD, delete the bad method and prepare to try again.
			// NOTE: This temporary solution only works if warehouse rejects the shipments and we will retry again (OWD only).
			// TODO: Fix the rate shopping for weight < 1lb
			if(BAD_SHIPPING_METHOD.equals(shipment.getStatus())
					&& !shipment.isInWarehouse()
					&& shippingMethods.size() == 1){
				ShippingMethod badShippingMethod = Iterables.getOnlyElement(shippingMethods);
				logger.info("Failed to push shipment {} it has a bad shipping method {}. Try deleting it",
						shipment.getId(), badShippingMethod.getSymphonyShippingCode());

				if(shipment.getPossibleShipMethods().contains(badShippingMethod)) {
					logger.info("Deleted bad shipping method {} from shipment {}.",
							badShippingMethod.getSymphonyShippingCode(), shipment.getId());
					shipment.getPossibleShipMethods().remove(badShippingMethod);
				}
			}

			logger.info("Shipment {} sent to warehouse", shipment.getId());
		} catch (Exception e){
			logger.error("Unknown exception while pushing shipment {} to warehouse", shipment.getId(), e);
			shipment.setStatus(UNKNOWN_ERROR_HOLD);
		}

		if(shipment.isInWarehouse()) {
			shipment.setInWarehouseAt(new Date());
			updateInventoryPostPushToWarehouse(shipment);
		}

		shipmentDao.saveOrUpdate(shipment);

		if(shipment.getStatus() == INVENTORY_NA){
			voidShipmentInWarehouse(shipment);
		} else {
			fireShipmentUpdateEvent(shipment);
		}
	}

	private boolean authorizeShipmentCharge(FulfillmentShipment shipment) {
		Long orderId = Long.parseLong(shipment.getOrderId());
		try {
			List<Long> chargeEventIds = chargeComponent.chargeOrder(orderId,
					ChargeEventExternalEntityType.SHIPMENT, shipment.getId().toString());
			logger.info("Shipment {} is paid and authorized to ship. Associated payments: {}",
					shipment.getId(), chargeEventIds.toString());
		} catch (Exception e) {
			logger.warn(format(
					"Shipment %d can't be pushed to warehouse. It does not have a valid payment.",
					shipment.getId()));
			signalFuseService.counter(Metrics.CounterMetricType.SHIPMENT_BLOCKED_BY_CHARGE_FAILURE)
					.inc();
			shipment.setStatus(FAILED_PAYMENT);
			shipmentDao.saveOrUpdate(shipment);
			fireShipmentUpdateEvent(shipment);
			return false;
		}
		return true;
	}

	private void updateInventoryPostPushToWarehouse(FulfillmentShipment shipment) {
		checkArgument(shipment.isInWarehouse(),
				format("The shipment %s must be in the warehouse", shipment.getId()));
		warehouseInventoryManager.updateInventory(shipment);
		updateChannelInventoryForBackorderedItems(shipment);
	}

	//TODO: This is a very ugly dependency on channel inventory, it must be removed soon.
	private void updateChannelInventoryForBackorderedItems(FulfillmentShipment shipment){
		for(FulfillmentItem item : shipment.getShipmentItems()){
			if(item.getVictoryLineItem().getStatus() == OrderItemStatus.BACK_ORDERED){
				for(Product product : item.getProducts()){
					boolean updated = productManager.decrementProductInventory(product, item.getQuantity(),
							ProductInventoryLog.ReasonCode.BACK_ORDERED_FULFILLMENT, false);
					if(!updated){
						logger.error(format("product %s has inventory %s, not enough for purchasing %s", product.getId
								(), product.getInventory(), item.getQuantity()));
						throw new ItemUnavailableException(item.getVictoryLineItem().getProductCluster(), false);
					}

					productManager.decrementBackOrderedInventory(product, item.getQuantity(),
							ProductInventoryLog.ReasonCode.BACK_ORDER_ADJUSTMENT_POST_FUL);
				}
			}
		}
	}

	private FulfillmentShipment overwriteShippingMethodsForPoBoxOrMilitaryAddress(FulfillmentShipment shipment) {
		AddressDTO addressDTO = new AddressDTO(shipment.getAddress());
		if(addressManager.isPOBox(addressDTO) || addressManager.isMilitaryAddress(addressDTO)) {
			ServiceLevel serviceLevel = shipment.getPossibleShipMethods().get(0).getServiceLevel();
			List<ShippingMethod> uspsShippingMethods
					= shipMethodManager.findByCarrierAndServiceLevel(ShippingCarrier.USPS, serviceLevel);
			shipment.getPossibleShipMethods().clear();
			shipment.getPossibleShipMethods().addAll(uspsShippingMethods);
		}
		return shipment;
	}

	private List<ShippingMethod> getShippingMethods(FulfillmentShipment shipment){
		List<ShippingMethod> shippingMethods = Lists.newArrayList();

		ShippingMethod cheapestShippingMethod = cheapestShippingMethodFor(shipment);
		if(cheapestShippingMethod != null) {
			shippingMethods.add(cheapestShippingMethod);
		} else {
			logger.info("Rate shopping failed for shipment {}. Try all methods", shipment.getId());
			shippingMethods.addAll(shipment.getPossibleShipMethods());
		}

		return shippingMethods;
	}

	@Override
	@Transactional
	public void updateAddress(@LogContext(ORDER_ID) String orderId, AddressDTO address) {
		logger.info("updating address for orderId {}. ", orderId);
		holdAndAbortIfFailedShipmentsExist(orderId);
		List<Long> shipmentIds = shipmentDao.findShipmentIdsByOrderId(orderId);
		if (shipmentIds.isEmpty()) {
			logger.info("Nothing to update for there are no shipments for orderId {}", orderId);
			return;
		}

		for(Long shipmentId : shipmentIds){
			FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
			if(!shipment.getStatus().canBeModified() || shipment.getWarehouse().isDropship()) {
				String message = format("Shipment %s cannot be updated", shipment);
				logger.warn(message);
				continue;
			}
			shipment.setAddress(constructMailAddress(address));
			if(shipment.isInWarehouse()) {
				logger.info("shipment {} is in warehouse, updating the address in warehouse", shipmentId);
				FulfillmentClient client = getFulfillmentClient(shipment);
				client.updateShipmentAddress(shipment, address);
				this.updateStatusFromWarehouse(shipmentId);
			}else{
				logger.info("shipment {} is NOT in warehouse, voiding the shipment", shipmentId);
				voidShipment(shipment);
			}

			logger.info("finish updating address for shipment {}", shipmentId);
		}
	}

	@Override
	public void updateShipmentShipMethod(@LogContext(SHIPMENT_ID) Long shipmentId, String symphonyShipCode) {
		ShippingMethod shippingMethod = shipMethodManager.findBySymphonyCode(symphonyShipCode);
		if(shippingMethod == null) {
			throw new SPException(format("Error with updating shipment %s: No valid ship method found for symphony ship code %s", shipmentId, symphonyShipCode));
		}
		FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
		holdAndAbortIfFailedShipment(shipment);
		shipment.getPossibleShipMethods().clear();
		shipment.getPossibleShipMethods().add(shippingMethod);
		if (shipment.isInWarehouse()) {
			getFulfillmentClient(shipment).updateShipmentShipMethod(shipment, shippingMethod);
		}
		shipmentDao.saveOrUpdate(shipment);
		fireShipmentUpdateEvent(shipment);
	}

	private MailAddress constructMailAddress(AddressDTO addressDTO){
		MailAddress address = new MailAddress(
				addressDTO.getFirstName(),
				addressDTO.getLastName(),
				addressDTO.getStreet1(),
				addressDTO.getStreet2(),
				addressDTO.getCity(),
				addressDTO.getState(),
				addressDTO.getZip(),
				addressDTO.getCountry(),
				addressDTO.getCompany(),
				addressDTO.getPhone(),
				addressDTO.getEmail(),
				addressDTO.getResidential());
		return address;
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public List<Long> getWarehouseShipmentIdsByStatus(Set<ServiceProvider> serviceProviders,
													  ShipmentStatus... statuses) {
		return shipmentDao.findWarehouseShipmentIdsByStatus(serviceProviders, statuses);
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public List<Long> getFillableFulfillmentOrderIdsByOrg(String org) {
		List<Long> orderIds = fulfillmentOrderDao.getFillableFulfillmentOrderIdsByOrg(org);
		logger.info("list of fillable FulfillmentOrderIds: {}", Arrays.toString(orderIds.toArray()));
		if(siteManager.findSite(org) != null && siteManager.findSite(org).getFulfillmentPlugin().isSkipProcessingBlockedOrders()) {
			logger.info("filtering ids");
			orderIds = orderIds.stream()
						.filter( orderId -> !blockedOrderCache.contains(orderId))
						.collect(Collectors.toList());
			logger.info("order ids without backorders: {}", Arrays.toString(orderIds.toArray()));
		}
		return orderIds;
	}

	@Override
	@Transactional
	public void updateLastProcessedTime(Long fulfillmentOrderId) {
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findById(fulfillmentOrderId);
		fulfillmentOrder.setLastProcessedTime(new Date());
		fulfillmentOrderDao.update(fulfillmentOrder);
	}

	@Override
	@Transactional (propagation = Propagation.REQUIRES_NEW)
	public Set<Long> createReadyShipments(Long fulfillmentOrderId) {
		return createReadyShipments(fulfillmentOrderId, null);
	}
	@Override
	@Transactional (propagation = Propagation.REQUIRES_NEW)
	public Set<Long> createReadyShipments(Long fulfillmentOrderId, WPISnapshot wpiSnapshot){
		if(wpiSnapshot==null) {
			FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findById(fulfillmentOrderId);
			Set<Long> productIds = fulfillmentOrderManager.findProductIds(Lists.newArrayList(fulfillmentOrderId));
			if(productIds.isEmpty()) {
				return Sets.newHashSet();
			}
			wpiSnapshot = wpiSnapshotFactory.createWPISnapshot(productIds, fulfillmentOrder.getOrg());
		}
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findById(fulfillmentOrderId);
		Collection<FulfillmentShipment> shipments = shipmentCreator.createShipments(fulfillmentOrder.getId(), wpiSnapshot);
		if (!shipments.isEmpty()) {
			shipmentDao.saveAll(shipments);
		}
		logger.info("created {} shipments for fulfillmentOrderId={}", shipments.size(), fulfillmentOrderId);
		return shipments.stream().map(FulfillmentShipment::getId).collect(toSet());
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public Collection<VictoryLineItem> getUnfulfilledItems(Long fulfillmentOrderId){
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findById(fulfillmentOrderId);
		Collection<VictoryLineItem> lineItems = shipmentCreator.getUnfulfilledVLIs(fulfillmentOrder.getId());
		return lineItems;
	}

	private void holdAndAbortIfFailedShipmentsExist(String orderId){
		List<Long> failedShipmentIds =
				shipmentDao.findShipmentIdsByOrderIdAndStatus(orderId, CREATED);
		if (!failedShipmentIds.isEmpty()) {
			holdAndAbort(shipmentDao.findByIds(failedShipmentIds));
		}
	}

	private void holdAndAbortIfFailedShipment(FulfillmentShipment shipment) {
		if(shipment.getStatus() == CREATED){
			holdAndAbort(Lists.newArrayList(shipment));
		}
	}

	// When we deal with a failed shipment, our system may be in a erroneous state allowing us to not
	// be able to do updates to DB. In such a situation, we don't want to operate on such a shipment. However,
	// its possible that shipment is in the warehouse and the user is trying to do cancellation/hold on it. The
	// most conservative thing we can do in such a situation is to simply hold this in the warehouse and shipment
	// recovery later on bring the shipment to correct state of being held.
	private void holdAndAbort(List<FulfillmentShipment> shipments) {
		shipments.forEach(shipment -> Preconditions.checkArgument(shipment.getStatus() == CREATED));

		shipments.forEach(shipment -> {
			try {
				getFulfillmentClient(shipment).holdShipment(shipment);
			} catch (Exception e) {
				logger.info("Failed to hold a failed shipment {} in the warehouse", shipment.getId());
			}
		});

		logger.info("This order contains failed shipments {} and can not be updated until we " +
						"have recovered them!", Ids.of(shipments));
		throw new FailedShipmentUpdateException("This order has been locked for modification because of unexpected events. " +
				"We will resolve this soon, please try after few hours.");
	}

	private void adjustFulfillmentOrderStatus(Long fulfillmentOrderId){
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findById(fulfillmentOrderId);

		FulfillmentOrderStatus oldStatus = fulfillmentOrder.getStatus();
		FulfillmentOrderStatus newStatus;

		Collection<VictoryLineItem> unfulfilledItems = getUnfulfilledItems(fulfillmentOrderId);
		if(!unfulfilledItems.isEmpty()) {
			newStatus = FulfillmentOrderStatus.PENDING;
		}else if(shipmentCreator.allOrderItemsCancelled(fulfillmentOrderId)){
			newStatus = FulfillmentOrderStatus.CANCELLED;
		}else{
			newStatus = FulfillmentOrderStatus.ALL_SHIPMENTS_CREATED;
		}

		if(newStatus != null && newStatus != oldStatus) {
			fulfillmentOrder.setStatus(newStatus);
			logger.info("Adjusted FulfillmentOrder[{}]'s status from {} to {}", fulfillmentOrder.getId(),
					oldStatus , newStatus);
			fulfillmentOrderDao.saveOrUpdate(fulfillmentOrder);

			signalFuseService.counter(newStatus.metricType()).inc();
		}
	}

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(READ_ONLY)
	public List<Long> getExternalUpdateShipmentIds() {
		return shipmentDao.findExternalUpdateShipmentIds();
	}

	@Override
	@Transactional(readOnly = true)
	public ShipmentUpdate getStatusUpdateFromWarehouse(@LogContext(SHIPMENT_ID) Long shipmentId) {
		FulfillmentShipment readOnlyShipment = readShipment(shipmentId);
		return getFulfillmentClient(readOnlyShipment).getStatusUpdateFromWarehouse(readOnlyShipment);
	}

	@Override
	public void shipDropshipWithPackage(Long shipmentId, PackageInfo packageInfo) {
		FulfillmentShipment shipment = readShipment(shipmentId);
		ShipmentUpdate shipmentUpdate = new ShipmentUpdate(DROPSHIP_SHIPPING);
		shipmentUpdate.setPackageInfoSet(Sets.newHashSet(packageInfo));
		applyShipmentUpdate(shipment, shipmentUpdate);
		updateChannelInventoryForBackorderedItems(shipment);
		FulfillmentOrder fulfillmentOrder = getFulfillmentOrderBySymphonyOrderId(Long.parseLong(shipment.getOrderId()));
		if(fulfillmentOrder != null) {
			adjustFulfillmentOrderStatus(fulfillmentOrder.getId());
		}
	}

	@Transactional
	private void applyShipmentUpdate(FulfillmentShipment shipment, ShipmentUpdate shipmentUpdate) {
		if(!shipmentUpdate.getShipmentStatus().canUpdateInternalStatus()) {
			return;
		}

		if(shipmentUpdate.getShipmentStatus() == SHIPPED ||
				shipmentUpdate.getShipmentStatus() == DROPSHIP_SHIPPING) {

			if(shipmentUpdate.getShipmentStatus() == SHIPPED) {
				trackTimeToShipShipment(shipment);
				shipment.setShippedAt(new Date());
			} else if(shipmentUpdate.getShipmentStatus() == DROPSHIP_SHIPPING) {
				signalFuseService.counter(Metrics.CounterMetricType.DROPSHIP_LABEL_CREATED).inc();
			}

			shipment.getPackages().clear();
			for(PackageInfo packageInfo : shipmentUpdate.getPackageInfoSet()) {
				String warehouseShipCode = packageInfo.getWarehouseShipCode();
				ShippingMethod symphonyShippingMethod = shipMethodManager.findByCode(warehouseShipCode);

				if(symphonyShippingMethod == null) {
					logger.warn(String.format("Unknown warehouseShipCode=%s", warehouseShipCode));
				}
				ShippingCarrier carrier = symphonyShippingMethod == null ? null : symphonyShippingMethod.getCarrier();
				Map<Product, Integer> contentProductQuantityMap = Maps.newHashMap();
				if(packageInfo.getContentSkuQuantityMap() != null) {
					for(String sku : packageInfo.getContentSkuQuantityMap().keySet()) {
						Product product = productManager.findBySku(sku);
						if(product != null) {
							contentProductQuantityMap.put(product, packageInfo.getContentSkuQuantityMap().get(sku));
						} else {
							logger.warn("In shipped shipment {}, cannot get a package's product info from sku {}",
									shipment.getId(), sku);
						}
					}
				}
				Package newPackage = new Package(packageInfo.getShippedTime(), packageInfo.getTrackingCode(),
						packageInfo.getLabelUrl(), carrier, symphonyShippingMethod,
						contentProductQuantityMap, packageInfo.getWeightInLbs(), packageInfo.getBillableWeightInLbs(),
						packageInfo.getRatedCost(), packageInfo.getHeightInInches(), packageInfo.getWidthInInches(),
						packageInfo.getDepthInInches(), shipment);
				shipment.getPackages().add(newPackage);
			}
		}

		if (shipment.getStatus() != shipmentUpdate.getShipmentStatus()) {
			if (shipmentUpdate.getShipmentStatus() == CANCELLED) {
				applyWarehouseInitiatedCancellation(shipment);
			}else {
				shipment.setStatus(shipmentUpdate.getShipmentStatus());
			}
		}
		shipmentDao.update(shipment);
		fireShipmentUpdateEvent(shipment);
	}

	private void fireShipmentUpdateEvent(FulfillmentShipment shipment) {
		if (shipment == null) {
			return;
		}

		Long shipmentId = shipment.getId();
		String orderId = shipment.getOrderId();

		if (shipmentId == null || orderId == null) {
			return;
		}

		try {
			eventBus.post(new EBShipmentUpdateEvent(shipmentId, new Long(orderId)));
		} catch (Exception e) {
			logger.warn("Could not fire ShipmentUpdate event for shipment id {} and order id {}", shipmentId, orderId);
		}
	}

	@Override
	public void adjustFulfillmentOrderStatusByOrderId(Long orderId){
		FulfillmentOrder fulfillmentOrder = getFulfillmentOrderBySymphonyOrderId(orderId);
		if(fulfillmentOrder != null) {
			adjustFulfillmentOrderStatus(fulfillmentOrder.getId());
		}
	}
	private void trackTimeToShipShipment(FulfillmentShipment shipment) {
		String warehouse = shipment.getWarehouse().getServiceProvider().getOrganizationHandle();
		DateTime inWarehouseAt = new DateTime(shipment.getInWarehouseAt());
		DateTime now = DateTime.now();
		Integer daysTaken = shipmentDurationCalculator.getDaysBetween(inWarehouseAt, now, warehouse);
		Integer secondsTaken = shipmentDurationCalculator.getSecondsBetween(inWarehouseAt, now, warehouse);
		signalFuseService.histogram(Metrics.HistogramMetricType.TIME_TO_SHIP)
				.withDimension(Metrics.Dimension.DAYS_TAKEN, daysTaken.toString())
				.withDimension(Metrics.Dimension.WAREHOUSE, warehouse)
				.put(secondsTaken);
		shipmentDao.saveOrUpdate(shipment);
	}

	private void applyWarehouseInitiatedCancellation(FulfillmentShipment shipment){
		String subject = format("Warehouse initiated cancellation on shipment %s", shipment.getId());
		logger.warn(subject);
		String body = format("We marked shipment %s CANCELLED on symphony side. Shiment content: %s",
				shipment.getId(), shipment);
		alertNinja(subject, body, null, FULFILLMENT_OPS);
		cancelEntireShipment(shipment);

		//update channel inventory
		for(FulfillmentItem item : shipment.getShipmentItems()) {
			item.getProductQuantities().entrySet().stream().forEach(entry -> {
				Product product = entry.getKey();
				Integer delta = entry.getValue();
				productManager.addProductInventory(product, delta,
						ProductInventoryLog.ReasonCode.WAREHOUSE_CANCELLATION);
				try {
					List<RequestLineItem> inventoryRequestLineItems = Lists.newArrayList(new RequestLineItem(product.getStockKeepingUnitId(),
							delta, item.getVictoryLineItem().getId().toString()));

					skuInventoryManager.restorePoolInventory(
							inventoryRequestLineItems,
							orderInventoryDetailManager.fetchPoolNameFromOrderId(Long.parseLong(shipment.getOrderId())),
							shipment.getOrderId(),
							ProductInventoryLog.ReasonCode.WAREHOUSE_CANCELLATION.name());
				} catch (FailedToRestorePoolInventoryException | UnknownPoolInventoryException
						| InventoryLineItemException e) {
					logger.info("POOL_INVENTORY_EXCEPTION : Failed to restore inventory for item {} upon warehouse initiated"+
							" cancellation with message {}", item.getVictoryLineItem(), e.toString());
				}
			});
		}

		//When we cancel a shipment, we first void it and that reserves inventory again if the shipment was in
		//warehouse. There are two cancellation flows in the system. One is via OrderManager that takes care of
		//unreserving inventory on its own. And ideally it should not worry about that, it should happen within
		//shipment management. But its already done that way and refactoring that would be a tricky effort and
		//would be handled as part of a refactoring effort of its own. For now, safest change is to just handle
		//this event in isolation and unreserve inventory here.
		shipment.getLineItems().stream().forEach(vli -> {
			if (vli.getStatus().isShippable()) {
				warehouseInventoryManager.unreserveInventory(vli,
						WarehouseInventoryReservationLog.Reason.WAREHOUSE_CANCELLED_SHIPMENT);
			}
		});
	}

	@Override
	@Transactional
	public boolean fulfillmentOrderExistsForOrder(@LogContext(ORDER_ID) Long orderId){
		//TODO: Use count(*) in a dao query to check for existence
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findBySymphonyOrderId(orderId.toString());
		return (fulfillmentOrder != null);
	}

	@Override
	@Transactional
	public Set<Long> cancelItemsByItemRefId(@LogContext(ORDER_ID) Long orderId, Set<Long> vliIds) {
		if(vliIds.isEmpty()){
			logger.info("Nothing to cancel for orderId {} as vliIds is empty", orderId);
		}

		logger.info("Cancelling items {} in order {}", vliIds, orderId);

		Set<Long> cancelledVliIds = Sets.newHashSet();

		//Lets set the status of fulfillmentOrder appropriately
		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findBySymphonyOrderId(orderId.toString());
		if(fulfillmentOrder == null){
			//TODO: throw a checked exception
			logger.info("There is no FulfillmentOrder for order {}", orderId);
			return cancelledVliIds;
		}

		List<Long> shipmentIds = getShipmentIdsByOrderId(orderId.toString());
		if(!shipmentIds.isEmpty()) {
			holdAndAbortIfFailedShipmentsExist(orderId.toString());
			for (Long shipmentId : shipmentIds) {
				cancelledVliIds.addAll(cancelShipmentItems(shipmentId, vliIds));
			}
		}

		adjustFulfillmentOrderStatus(fulfillmentOrder.getId());
		return cancelledVliIds;
	}

	@Override
	public void addLineItems(Long orderId, List<OrderLineItemDTO> orderLineItemDTOs) {
		FulfillmentOrder fulfillmentOrder = getFulfillmentOrderBySymphonyOrderId(orderId);
		if (fulfillmentOrder == null) {
			return;
		}
		adjustFulfillmentOrderStatus(fulfillmentOrder.getId());
	}

	@Override
	public Set<Long> extractVLIsNotInWarehouse(Set<Long> vliIdsForCancellation){
		Set<Long> vliIdsInWarehouse = Sets.newHashSet();
		List<FulfillmentItem> fulfillmentItems = shipmentItemDao.findByVLIs(vliIdsForCancellation);
		for(FulfillmentItem item : fulfillmentItems){
			if(item.getShipment().isInWarehouse()){
				vliIdsInWarehouse.add(item.getVictoryLineItem().getId());
			}
		}
		Set<Long> vliIdsNotInWarehouse= Sets.difference(vliIdsForCancellation, vliIdsInWarehouse);
		return vliIdsNotInWarehouse;
	}

	@Override
	@Transactional
	public void updateShipment(@LogContext(SHIPMENT_ID) FulfillmentShipment shipment){
		shipmentDao.saveOrUpdate(shipment);
		fireShipmentUpdateEvent(shipment);
	}

	@Override
	public void updateWarehouseShippingMethod(FulfillmentShipment shipment){
		if(!shipment.isInWarehouse()){
			throw new IllegalStateException(format("Shipment %s is not in warehouse", shipment.getId()));
		}

		final ShippingMethod shippingMethod = Iterables.getFirst(shipment.getPossibleShipMethods(), null);
		if(shippingMethod == null){
			logger.error("There is no possible shipping method for shipment {}", shipment.getId());
			return;
		}

		fulfillmentClientFactory.getFulfillmentClient(shipment.getWarehouse()).updateShipmentShipMethod(
				shipment, shippingMethod
		);
		shipment.setStatus(WAREHOUSE);
		updateShipment(shipment);
	}

	private FulfillmentClient getFulfillmentClient(FulfillmentShipment readOnlyshipment) {
		return fulfillmentClientFactory.getFulfillmentClient(readOnlyshipment.getWarehouse());
	}

	private Set<Long> cancelShipmentItems(@LogContext(SHIPMENT_ID) Long shipmentId, Set<Long> vliIdSet) {
		Set<Long> cancelledVliIds = Sets.newHashSet();
		if(vliIdSet.isEmpty()) {
			return cancelledVliIds;
		}

		FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
		if(!shipment.getStatus().canBeModified()){
			logger.info("Shipment {} is already in final state {}", shipmentId, shipment.getStatus());
			return cancelledVliIds;
		}

		if(!shipment.getStatus().canBeCancelled()) {
			String illegalUpdateError = format("Shipment %s cannot be cancelled, because it currently has status %s",
					shipment.getId(), shipment.getStatus());
			logger.warn(illegalUpdateError);
			throw new IllegalUpdateException(illegalUpdateError);
		}

		logger.info("Cancelling items {} from shipment {}", vliIdSet, shipmentId);
		Set<FulfillmentItem> itemsToCancel = Sets.newHashSet();
		Set<FulfillmentItem> itemsToRetain = Sets.newHashSet();
		for(FulfillmentItem item : shipment.getShipmentItems()){
			if(vliIdSet.contains(item.getVictoryLineItem().getId())){
				itemsToCancel.add(item);
				cancelledVliIds.add(item.getVictoryLineItem().getId());
			}else{
				itemsToRetain.add(item);
			}
		}

		if(itemsToCancel.isEmpty()){
			return cancelledVliIds;
		}

		if(shipment.isInWarehouse()) {
			logger.info("Cancelling shipment {} in warehouse", shipment.getId());
			getFulfillmentClient(shipment).cancelShipment(shipment);
		}

		boolean cancelled = cancelShipmentPartially(shipment, itemsToRetain);
		if(!cancelled){
			logger.info("Failed to cancel items in shipment {}", shipment.getId());
			cancelledVliIds.clear();
			return cancelledVliIds;
		}

		return cancelledVliIds;
	}

	private boolean cancelEntireShipment(FulfillmentShipment shipment){
		return cancelShipmentPartially(shipment, EMPTY_SET);
	}

	private boolean cancelShipmentPartially(FulfillmentShipment shipment, Set<FulfillmentItem> itemsToRetain){
		logger.info("Cancelling shipment {}, itemsToRetain {}", shipment.getId(), itemsToRetain);
		boolean shipmentIsInWarehouse = shipment.isInWarehouse();
		voidShipment(shipment);
		shipment.removeItems(itemsToRetain);
		shipmentDao.save(shipment);

		if(shipmentIsInWarehouse){
			for(FulfillmentItem item : itemsToRetain){
				item.getVictoryLineItem().setStatus(OrderItemStatus.PENDING, true);
			}
		}

		//Lets delete the fulfillment line items for victory line items that need to be retained.
		for(FulfillmentItem item : itemsToRetain){
			item.setShipment(null);
			shipmentItemDao.delete(item);
		}

		shipment.setStatus(CANCELLED);
		warehouseInventoryManager.updateInventory(shipment);
		return true;
	}

	/**
	 * This would void a shipment and update WarehouseInventory and WarehouseInventoryReservation tables for all
	 * PENDING line items in the shipment. Please make sure that the line item statuses are already set to what you
	 * want them to be post voiding.
	 * @param shipment
	 */
	@Override
	@Transactional
	public void voidShipmentInWarehouse(@LogContext(SHIPMENT_ID) FulfillmentShipment shipment) throws IllegalUpdateException {
		logger.info("Voiding shipment {}", shipment.getId());
		final ShipmentStatus status = shipment.getStatus();
		if(!status.canBeModified() || status == CREATED){
			String message = format("Shipment %s [status = %s] can't be modified", shipment.getId(), status);
			throw new IllegalUpdateException(message);
		}

		if(shipment.isInWarehouse()) {
			ShipmentUpdate statusUpdate = getFulfillmentClient(shipment).getStatusUpdateFromWarehouse(shipment);
			logger.info("Shipment {} has status {} in warehouse", shipment.getId(), statusUpdate.getShipmentStatus());
			if (!statusUpdate.getShipmentStatus().equals(CANCELLED)) {
				logger.info("Cancelling Shipment {} in warehouse", shipment);
				getFulfillmentClient(shipment).cancelShipment(shipment);
			}
		}

		voidShipment(shipment);

		if(status.equals(INVENTORY_NA)) {
			// If we come here during the shipment creation and we end up throwing an exception downstream from here,
			// we will have the recovery job restore the shipment status from the warehouse to INVENTORY_NA and then
			// we have a job for INVENTORY_NA that will pick up the shipment and void in the warehouse.
			shipment.getProducts().stream().distinct()
					.forEach(product -> {
						warehouseInventoryAuditManager.auditWarehouseInventory(product.getId(), true);
						channelInventoryAuditManager.auditChannelInventory(product.getId());
					});
		}
	}

	@Transactional
	private void voidShipment(@LogContext(SHIPMENT_ID) FulfillmentShipment shipment) throws IllegalUpdateException{
		shipment.setStatus(VOIDED);
		shipmentDao.update(shipment);
		fireShipmentUpdateEvent(shipment);
		warehouseInventoryManager.updateInventory(shipment);

		//After the inventory deductions have happened, its time to mark this shipment to be not in warehouse.
		shipment.setInWarehouse(false);
		shipmentDao.update(shipment);

		FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findBySymphonyOrderId(shipment.getOrderId());
		adjustFulfillmentOrderStatus(fulfillmentOrder.getId());
	}

	private ShippingMethod cheapestShippingMethodFor(FulfillmentShipment shipment){
		ShippingProfile shippingProfile = new ShippingProfile();
		shippingProfile.setShippingParcel(ShippingParcel.DEFAULT_RATE_SHOPPING_PARCEL);
		try{
			BigDecimal totalWeight = getTotalWeightOfShipmentInOunces(shipment);
			if(BigDecimal.ZERO.equals(totalWeight)) {
				logger.info("Cannot find correct weight of shipment {}", shipment.getId());
				return null;
			}

			shippingProfile.setWeight(totalWeight);

			try {

				String ruleReferenceName = shipment.getRuleReferenceName();
				Organization organization = organizationManager.findBySubdomain(ruleReferenceName);

				FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findBySymphonyOrderId(shipment.getOrderId());
				Date orderCreationDate = fulfillmentOrder.getOriginalOrderCreationTime();
				RateShoppingRecordDTO rateShoppingRecordDTO = fulfillmentRateManager.findCheapestFilteredRate(
						new AddressDTO(shipment.getWarehouse().getAddress()),
						new AddressDTO(shipment.getAddress()),
						totalWeight.setScale(0, RoundingMode.CEILING).intValue(),
						ShipmentUtils.methodsToSymphonyCodes(shipment.getPossibleShipMethods()),
						new ShippingRateOptional(shipment.getWarehouse().getSmartpostHubId()), organization,
						orderCreationDate);
				ShippingMethod cheapestShippingMethod = shipMethodManager.findShipMethodById(rateShoppingRecordDTO.getCheapestShippingMethod_id());

				// save the shipment/record pair
				logger.info("Saving Rate Shopping result {} for shipment {}", rateShoppingRecordDTO.getId(), shipment.getId());
				ShipmentRateShopping shipmentRateShopping = new ShipmentRateShopping(shipment.getId(), rateShoppingRecordDTO.getId());
				rateShoppingRecordManager.save(shipmentRateShopping);

				return cheapestShippingMethod;

			} catch (UnavailableRateException | InvalidAddressException ure) {
				return null;
			}
		} catch(Exception e) {
			logger.warn(format("Exception thrown for FulfillmentShipment: %d", shipment.getId()), e);
			return null;
		}
	}

	@Transactional
	public BigDecimal getTotalWeightOfShipmentInOunces(FulfillmentShipment shipment) {
		BigDecimal totalWeight = BigDecimal.ZERO;
		IntegerMap<Product> productQuantityMap = shipment.getQuantities();
		for(Product product : productQuantityMap.keySet()) {
			// if no weight information available in db, fetch from warehouse
			BigDecimal weight = product.getShippingWeight();
			if(weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
				weight = productManager.getAndUpdateWeightInOuncesFromWarehouse(product);
			}
			if(weight == null) {
				throw new UnavailableWeightException(format("no weight available for product %s, in shipment %s", product, shipment.getId()));
			} else {
				BigDecimal units = new BigDecimal(productQuantityMap.get(product));
				totalWeight = totalWeight.add(weight.multiply(units));
			}
		}
		return totalWeight;
	}

	@Transactional
	private void updateFulfillmentShipmentStatus(@LogContext(SHIPMENT_ID) Long shipmentId, ShipmentStatus status) {
		FulfillmentShipment fulfillmentShipment = getShipmentForUpdate(shipmentId);
		try {
			fulfillmentShipment.setStatus(status);
		} catch (OrderStatusException e) {
			logger.error(format("Cannot change status from %s to %s",
					fulfillmentShipment.getStatus().toString(), status.toString()), e);
		}
		shipmentDao.update(fulfillmentShipment);
		fireShipmentUpdateEvent(fulfillmentShipment);

		FulfillmentOrder fulfillmentOrder = getFulfillmentOrderBySymphonyOrderId(Long.parseLong(fulfillmentShipment.getOrderId()));
		if(fulfillmentOrder != null) {
			adjustFulfillmentOrderStatus(fulfillmentOrder.getId());
		}

		logger.info("Updated shipment {} status from {} to {}",
				shipmentId, fulfillmentShipment.getStatus(), status);
	}

	@Override
	public Long createDropshipShipment(Order order, Set<VictoryLineItem> lineItems) {
		return shipmentCreator.createDropshipShipment(order, lineItems);
	}

	@Override
	public void voidDropshipShipment(Long shipmentId) {
		logger.info("Setting FulfillmentShipment {} status to VOIDED", shipmentId);
		updateFulfillmentShipmentStatus(shipmentId, VOIDED);
	}

	@Override
	public void setDropshipShipmentToShipped(Long shipmentId) {
		logger.info("Setting FulfillmentShipment {} status to SHIPPED", shipmentId);
		updateFulfillmentShipmentStatus(shipmentId, SHIPPED);
	}

	@Override
	@Transactional
	public void updateWarehouseNoteByOrderId(@LogContext(ORDER_ID) String orderId, String warehouseNote) {
		FulfillmentOrder fulfillmentOrder = getFulfillmentOrderBySymphonyOrderId(Long.parseLong(orderId));
		fulfillmentOrder.setWarehouseNote(warehouseNote);
		fulfillmentOrderDao.update(fulfillmentOrder);
	}

	@Override
	@Transactional
	public void updatePackDefaultInvoiceByOrderId(@LogContext(ORDER_ID) String orderId, Boolean packDefaultInvoice) {
		FulfillmentOrder fulfillmentOrder = getFulfillmentOrderBySymphonyOrderId(Long.parseLong(orderId));
		fulfillmentOrder.setPackDefaultInvoice(packDefaultInvoice);
		fulfillmentOrderDao.update(fulfillmentOrder);
	}

	@Transactional
	private ShipmentUpdate updateStatusFromWarehouse(@LogContext(SHIPMENT_ID) FulfillmentShipment shipment) {
		logger.info("updating shipment {} with new status", shipment);
		final ShipmentUpdate shipmentUpdate = getFulfillmentClient(shipment).getStatusUpdateFromWarehouse(shipment);
		logger.info("shipment {} has update from warehouse : {}", shipment.getId(), shipmentUpdate);
		if(shipmentUpdate == null || shipment.getStatus() == shipmentUpdate.getShipmentStatus()) {
			logger.info("Shipment {} has no change in status, there is nothing to update.", shipment.getId());
			return null;
		}
		logger.info("shipment {} has status changed from {} to {}, updating the status", shipment.getId(),
				shipment.getStatus(), shipmentUpdate.getShipmentStatus());
		applyShipmentUpdate(shipment, shipmentUpdate);
		return shipmentUpdate;
	}

	@Override
	@Transactional
	public ShipmentUpdate updateStatusFromWarehouse(@LogContext(SHIPMENT_ID) final Long shipmentId) {

		ShipmentUpdate shipmentUpdate = transactionManager.inItsOwnTxWithResult(() -> {
			FulfillmentShipment shipment = getShipmentForUpdate(shipmentId);
			return updateStatusFromWarehouse(shipment);
		});

		transactionManager.inItsOwnTx(s -> {
			packageManager.findPackagesByShipmentId(shipmentId).forEach(singlePackage ->
					packageTrackingManager.createTrackingStatusIfNotExist(new PackageDTO(packageManager.findById(singlePackage.getId()))));

		});
		return shipmentUpdate;
	}

	@Override
	@Transactional
	@Deprecated
	public int getUnitsFilledAndUnderReview(Long productId, String poolName) {
		Product product = productManager.findById(productId);
		List<ProductCluster> productParentKits =  productManager.findParentKits(product);
		logger.info("count lineItems for product {}, using all productParentKits {}", product.getId(), productParentKits);
		return shipmentItemDao.getSumOfUnitsUnderReviewInShipments(product, poolName, productParentKits);
	}

	@Override
	@Transactional
	@DatabaseGroup(READ_ONLY)
	public List<Long> findLatestShipmentIds(int rangeLength) {
		return shipmentDao.findLatestIds(rangeLength);
	}

	@Override
	@Transactional
	public List<FulfillmentItem> findByVLIs(Set<Long> vliSet) {
		return shipmentItemDao.findByVLIs(vliSet);
	}
}