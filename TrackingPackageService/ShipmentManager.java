package sneakpeeq.core.managers.FAS;

import org.joda.time.DateTime;
import sneakpeeq.core.annotations.DataAccessContext;
import sneakpeeq.core.annotations.DatabaseGroup;
import sneakpeeq.core.annotations.LogContext;
import sneakpeeq.core.entity.FAS.enums.ShipmentStatus;
import sneakpeeq.core.entity.WPISnapshot;
import sneakpeeq.core.entity.OrderLineItemDTO;
import sneakpeeq.core.entity.shared.AddressDTO;
import sneakpeeq.core.models.FAS.FulfillmentItem;
import sneakpeeq.core.models.FAS.FulfillmentOrder;
import sneakpeeq.core.models.FAS.FulfillmentShipment;
import sneakpeeq.core.models.Order;
import sneakpeeq.core.models.Product;
import sneakpeeq.core.models.VictoryLineItem;
import sneakpeeq.core.models.fulfillment.ServiceProvider;
import sneakpeeq.core.pojos.PackageInfo;
import sneakpeeq.core.pojos.ShipmentUpdate;
import sneakpeeq.core.util.IntegerMap;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.Multimap;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import static sneakpeeq.core.annotations.DataAccessContext.READ_ONLY;
import static sneakpeeq.core.annotations.LogContext.LogContextField.ORDER_ID;
import static sneakpeeq.core.annotations.LogContext.LogContextField.SHIPMENT_ID;

@Validated
public interface ShipmentManager {
	// these 3 functions should be the only ones that return FulfillmentShipment(s)
	List<FulfillmentShipment> readShipments(Collection<Long> shipmentIds);

	FulfillmentShipment readShipment(Long shipmentId);
	FulfillmentShipment getShipmentForUpdate(Long shipmentId);
	Set<String> findOrgsWithUnfilledOrders();
	void updateLastProcessedTime(Long fulfillmentOrderId);
	Long createDropshipShipment(Order order, Set<VictoryLineItem> lineItems);
	void voidDropshipShipment(Long shipmentId);
	void setDropshipShipmentToShipped(Long shipmentId);
	List<Long> getShipmentIdsByLineItemIds(List<Long> vliIds);
	void holdShipmentByShipmentId(Long shipmentId);
	void holdShipmentByOrderId(String orderId);
	ShipmentUpdate releaseShipmentByShipmentId(Long shipmentId);
	List<Long> getExternalUpdateShipmentIds();
	void updateWarehouseNoteByOrderId(String orderId, String warehouseNote);
	void updatePackDefaultInvoiceByOrderId(String orderId, Boolean packDefaultInvoice);
	ShipmentUpdate getStatusUpdateFromWarehouse(Long shipmentId);
	void updateShipment(FulfillmentShipment shipment);
	IntegerMap<Product> getQuantityMap(Long shipmentId);
	List<FulfillmentItem> findByVLIs(Set<Long> vliSet);

	/**
	 * Compute number of units for the given product against line items that are in shipments but
	 * not in warehouse yet.
	 *
	 * @param productId
	 * @param poolName
	 * @return
	 */
	int getUnitsFilledAndUnderReview(Long productId, String poolName);

	Collection<FulfillmentShipment> getShipmentsByOrderId(@LogContext(ORDER_ID) String orderId);
	Multimap<String, FulfillmentShipment> getShipmentsByOrderIds(List<String> orderIds);
	List<Long> getShipmentIdsByOrderId(String orderId);
	Set<Long> createReadyShipments(Long fulfillmentOrderId, WPISnapshot wpiSnapshot);
	Set<Long> createReadyShipments(Long fulfillmentOrderId);

	void createFulfillmentOrder(Order order);
	FulfillmentOrder getFulfillmentOrderBySymphonyOrderId(Long orderId);
	// Do not use transaction here. This may throw a runtime exception and mark the outer TX method as rollback-only,
    // although the outer method catch and ignore the exception.
	void pushShipmentToWarehouse(FulfillmentShipment shipment);
	ShipmentStatus getShipmentStatusByShipmentId(Long shipmentId);
	void releaseShipmentByOrderId(String orderId);
	Collection<VictoryLineItem> getUnfulfilledItems(Long fulfillmentOrderId);
	List<Long> getFillableFulfillmentOrderIdsByOrg(String clientRef);
	void updateAddress(String orderId, AddressDTO address);
	ShipmentUpdate updateStatusFromWarehouse(Long shipmentId);
	void shipDropshipWithPackage(Long shipmentId, PackageInfo packageInfo);
	Collection<FulfillmentShipment> sendToWarehouse(Collection<Long> shipmentIds, Long fulfillmentOrderId);
	void addLineItems(Long orderId, List<OrderLineItemDTO> orderLineItemDTOs);
	Map<Long,Optional<ShipmentUpdate>> recoverShipmentStatusIfNecessary(Long fulfillmentOrderId);
	Set<Long> extractVLIsNotInWarehouse(Set<Long> vliIdsForCancellation);
	Set<Long> cancelItemsByItemRefId(Long orderId, Set<Long> vliIds);

	void updateWarehouseShippingMethod(FulfillmentShipment shipment);
	Map<Long, List<Long>> getShipmentIdsMapByLineItemIds(List<Long> vliIds);

	@Transactional(readOnly = true)
	@DatabaseGroup(DataAccessContext.READ_ONLY)
	List<Long> getWarehouseShipmentIdsByStatus(Set<ServiceProvider> serviceProviders,
											   ShipmentStatus... statuses);

	void updateShipmentShipMethod(@LogContext(SHIPMENT_ID) Long shipmentId, String symphonyShipCode);


	@Transactional
	boolean fulfillmentOrderExistsForOrder(Long orderId);

	void voidShipmentInWarehouse(FulfillmentShipment shipment);

	BigDecimal getTotalWeightOfShipmentInOunces(FulfillmentShipment shipment);

	@Transactional(READ_ONLY)
	List<Long> findLatestShipmentIds(int rangeLength);
	FulfillmentOrder findById(Long id);
	FulfillmentShipment findByTrackingNumber(String trackingNumber);
	void adjustFulfillmentOrderStatusByOrderId(Long orderId);
}
