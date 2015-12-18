package sneakpeeq.sharedweb.controllers;

import sneakpeeq.core.billing.BillingManager;
import sneakpeeq.core.cache.FulfillmentBlockedOrderCache;
import sneakpeeq.core.daos.FAS.FulfillmentOrderDao;
import sneakpeeq.core.daos.FAS.WarehouseInventoryMovementDao;
import sneakpeeq.core.daos.ProductDao;
import sneakpeeq.core.entity.AddressVerificationDTO;
import sneakpeeq.core.daos.VictoryLineItemDao;
import sneakpeeq.core.entity.FAS.enums.ShipmentStatus;
import sneakpeeq.core.managers.AddressManager;
import sneakpeeq.core.managers.CartLineItemManager;
import sneakpeeq.core.managers.FAS.ASNManager;
import sneakpeeq.core.managers.FAS.DuplicateShipmentDetector;
import sneakpeeq.core.managers.FAS.FulfillmentJobManager;
import sneakpeeq.core.managers.FAS.InventoryLineItemAuditor;
import sneakpeeq.core.managers.FAS.ReturnManager;
import sneakpeeq.core.managers.FAS.ShipmentJobHelper;
import sneakpeeq.core.managers.FAS.ShipmentManager;
import sneakpeeq.core.managers.FAS.WarehouseInventoryManager;
import sneakpeeq.core.managers.FAS.WarehouseInventoryMovementManager;
import sneakpeeq.core.managers.FAS.WarehouseManager;
import sneakpeeq.core.managers.FAS.WarehousePoolInventoryAuditor;
import sneakpeeq.core.managers.InventoryManager;
import sneakpeeq.core.managers.OrderInventoryDetailManager;
import sneakpeeq.core.managers.OrderingManager;
import sneakpeeq.core.managers.ProductManager;
import sneakpeeq.core.managers.TransactionManager;
import sneakpeeq.core.managers.VictoryManager;
import sneakpeeq.core.managers.fulfillment.FulfillmentManager;

import com.symphonycommerce.inventory.daos.InventoryLineItemDao;
import com.symphonycommerce.inventory.daos.InventoryPoolDao;
import com.symphonycommerce.inventory.daos.PoolInventoryDao;
import com.symphonycommerce.inventory.exceptions.DatabaseUpdateException;
import com.symphonycommerce.inventory.exceptions.InventoryLineItemException;
import com.symphonycommerce.inventory.managers.InventoryLineItemManager;
import com.symphonycommerce.inventory.models.InventoryLineItem;
import com.symphonycommerce.inventory.pojos.RequestLineItem;
import com.symphonycommerce.lib.metrics.Metrics;
import sneakpeeq.core.models.FAS.FulfillmentOrder;
import sneakpeeq.core.models.FAS.FulfillmentShipment;
import sneakpeeq.core.models.FAS.WarehouseInventory;
import sneakpeeq.core.models.FAS.WarehouseInventoryMovement;
import sneakpeeq.core.models.FAS.WarehouseInventoryMovementDTO;
import sneakpeeq.core.models.MailAddress;
import sneakpeeq.core.models.Product;
import sneakpeeq.core.models.ProductInventoryLog;
import sneakpeeq.core.models.VictoryLineItem;
import sneakpeeq.core.models.fulfillment.ServiceProvider;
import sneakpeeq.core.models.fulfillment.Warehouse;
import sneakpeeq.core.models.fulfillment.bulkship.InventoryMovement;
import sneakpeeq.core.util.FAS.CommercialInvoiceCalculator;

import com.symphonycommerce.inventory.daos.StockKeepingUnitDao;
import com.symphonycommerce.inventory.managers.SkuInventoryManager;
import sneakpeeq.sharedweb.auth.SecuredAccess;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import static java.lang.Long.parseLong;
import static sneakpeeq.core.managers.FAS.DuplicateShipmentDetector.MissingShipmentsReport;
import static java.util.stream.Collectors.toList;

@Controller
@SecuredAccess
@RequestMapping("/org/{org}/api/v1/fulfillment")
public class FulfillmentApiController extends GenericController {

	private static final Long BATCH_SIZE = 1000L;

	@Autowired private FulfillmentManager fulfillmentManager;
	@Autowired private FulfillmentJobManager fulfillmentJobManager;
	@Autowired private ShipmentManager shipmentManager;
	@Autowired private ShipmentJobHelper shipmentJobHelper;
	@Autowired private ASNManager asnManager;
	@Autowired private DuplicateShipmentDetector duplicateShipmentDetector;
	@Autowired private WarehouseInventoryManager warehouseInventoryManager;
	@Autowired private WarehouseInventoryMovementManager warehouseInventoryMovementManager;
	@Autowired private WarehouseInventoryMovementDao warehouseInventoryMovementDao;
	@Autowired private OrderingManager orderingManager;
	@Autowired private FulfillmentOrderDao fulfillmentOrderDao;
	@Autowired private InventoryManager inventoryManager;
	@Autowired private CartLineItemManager cartLineItemManager;
	@Autowired private ProductManager productManager;
	@Autowired private WarehouseManager warehouseManager;
	@Autowired private ReturnManager returnManager;
	@Autowired private BillingManager billingManager;
	@Autowired private SkuInventoryManager skuInventoryManager;
	@Autowired private InventoryLineItemManager inventoryLineItemManager;
	@Autowired private WarehousePoolInventoryAuditor warehousePoolInventoryAuditor;
	@Autowired private AddressManager addressManager;
	@Autowired private InventoryLineItemAuditor inventoryLineItemAuditor;
	@Autowired private VictoryManager victoryManager;
	@Autowired private OrderInventoryDetailManager orderInventoryDetailManager;
	@Autowired private TransactionManager ownTxWrapper;

	@Autowired private InventoryPoolDao inventoryPoolDao;
	@Autowired private StockKeepingUnitDao stockKeepingUnitDao;
	@Autowired private PoolInventoryDao poolInventoryDao;
	@Autowired private ProductDao productDao;
	@Autowired private VictoryLineItemDao victoryLineItemDao;
	@Autowired private InventoryLineItemDao inventoryLineItemDao;
	@Autowired private FulfillmentBlockedOrderCache blockedOrderCache;

	// This is for testing the script server, may be used to manually run the order too
	/**
	 * shipment jobs. We can call the function directly to issue a push/update/settle with one shipment
	 * */
	@RequestMapping(value = { "ship-dropship-items" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void shipDropshipItems(@PathVariable String org) {
		List<VictoryLineItem> vlis = cartLineItemManager.findLineItemsInShippingToBeShipped();
		orderingManager.shipDropshipItems(vlis);
	}

	@RequestMapping(value = { "create-shipments" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public Map<String, String> createShipments(@PathVariable String org, @RequestParam Long orderId) {
		Map<String, String> retMap = Maps.newHashMap();
		try{
			FulfillmentOrder fulfillmentOrder = fulfillmentOrderDao.findBySymphonyOrderId(orderId.toString());
			fulfillmentJobManager.createShipmentsAndSendToWarehouse(fulfillmentOrder.getId());
			countByStoreFront(Metrics.CounterMetricType.ORDERS_SHIPPING);
			retMap.put(orderId.toString(), "SUCCESS");
		} catch (Exception e) {
			retMap.put(orderId.toString(), "FAILED - " + e.getMessage());
		}
		return retMap;
	}

	@RequestMapping(value = { "update-shipment-status-from-warehouse" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void updateShipmentStatusFromWarehouse(@PathVariable String org, @RequestParam Long shipmentId) {
		fulfillmentJobManager.updateShipmentStatusFromWarehouse(shipmentId);
	}


	@RequestMapping(value = { "release-failed-shipment" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void releaseFailedShipment(@PathVariable String org, @RequestParam Long shipmentId) throws Exception {
		ShipmentStatus shipmentStatus = shipmentManager.readShipment(shipmentId).getStatus();
		if (shipmentStatus == ShipmentStatus.BAD_SHIPPING_METHOD) {
			fulfillmentJobManager.retryFailedShipment(shipmentId);
		} else if (shipmentStatus == ShipmentStatus.FAILED_PAYMENT) {
			fulfillmentJobManager.releaseShipment(shipmentId);
		} else {
			fulfillmentJobManager.releaseFailedShipment(shipmentId);
		}
	}

	@RequestMapping(value = { "update-status-from-warehouse" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public Map<String, String> updateStatusFromWarehouse(@PathVariable String org, @RequestParam Long orderId) {
		Map<String, String> retMap = Maps.newHashMap();
		try{
			for(Long shipmentId : shipmentManager.getShipmentIdsByOrderId(orderId.toString())){
				fulfillmentJobManager.updateShipmentStatusFromWarehouse(shipmentId);
			}
			retMap.put(orderId.toString(), "SUCCESS");
		} catch (Exception e) {
			retMap.put(orderId.toString(), "FAILED - " + e.getMessage());
		}
		return retMap;
	}

	@RequestMapping(value = { "update-shipment-shipmethod" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void updateShipmentShipMethod(@PathVariable String org, @RequestParam Long shipmentId, @RequestParam String symphonyShipCode) {
		shipmentManager.updateShipmentShipMethod(shipmentId, symphonyShipCode);
		FulfillmentShipment shipment = shipmentManager.readShipment(shipmentId);
		if(shipment.isInWarehouse()) {
			fulfillmentJobManager.updateShipmentStatusFromWarehouse(shipmentId);
		}
	}

	@RequestMapping(value = { "update-expired-backorder" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void updateExpiredBackOrder(@PathVariable String org) {
		fulfillmentManager.updateShipDateForExpiredBackOrder(BATCH_SIZE);

	}

	@RequestMapping(value = { "update-expired-scheduled-order" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void updateExpiredScheduledOrder(@PathVariable String org) {
		fulfillmentManager.updateShipDateForExpiredScheduledOrder(BATCH_SIZE);
	}

	@RequestMapping(value = { "run-warehouse-inventory-movement-job" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void runWarehouseInventoryMovementJob(@PathVariable String org) {
		warehouseInventoryMovementManager.syncAllWarehouseInventoryMovements();
	}

	//valid call example:
	//http://devpartner.sneakpeeq.com:8080/org/partner/api/v1/fulfillment/run-warehouse-inventory-movement-job-with-date?serviceProvider=OWD&startDate=2014-08-01-01:01:01&endDate=2014-08-02-01:01:01
	@RequestMapping(value = { "run-warehouse-inventory-movement-job-with-date" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void runWarehouseInventoryMovementJob(@PathVariable String org, @RequestParam ServiceProvider serviceProvider,
	                                    @RequestParam @DateTimeFormat(pattern="yyyy-MM-dd-hh:mm:ss")DateTime startDate,
	                                    @RequestParam @DateTimeFormat(pattern="yyyy-MM-dd-hh:mm:ss")DateTime endDate) {
		warehouseInventoryMovementManager.syncWarehouseInventoryMovements(serviceProvider, startDate, endDate);
	}

	@RequestMapping(value = { "testInventoryMovement" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void testInventoryMovement(@PathVariable String org) {
		InventoryMovement movement = new InventoryMovement("P1234", 10, ProductInventoryLog.ReasonCode.ADD_STOCK, "234556",
				new Date(), ServiceProvider.ATLAST);
	}

	@Transactional
	@RequestMapping(value = { "testWarehouseInventoryMovement" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void testWarehouseInventoryMovement(@PathVariable String org, @RequestParam Long productId,
											   @RequestParam String warehouseCode, @RequestParam int delta,
											   @RequestParam String serviceProvider, @RequestParam boolean isPickable,
											   @RequestParam String eventId) {
		WarehouseInventoryMovementDTO movement = new WarehouseInventoryMovementDTO();
		movement.setProductId(productId);
		movement.setWarehouseCode(warehouseCode);
		movement.setDelta(delta);
		movement.setServiceProvider(ServiceProvider.valueOf(serviceProvider));
		movement.setPickable(isPickable);
		movement.setWarehouseEventId(eventId);
		movement.setReason(WarehouseInventoryMovement.Reason.ADJUSTMENT);
		warehouseInventoryMovementManager.syncWarehouseInventoryMovement(movement);
	}

	/**
	 * ASN management
	 * */
	@RequestMapping(value = { "update-asn-status-with-warehouse" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void syncASNStatus(@PathVariable String org, @RequestParam Long asnId) {
		asnManager.updateASNStatusFromWarehouse(asnId, getSubdomain());
	}

	@RequestMapping(value = { "update-warehouse-inventory" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void updateWarehouseInventory(@PathVariable String org, @RequestParam Long shipmentId) {
		FulfillmentShipment shipment = shipmentManager.readShipment(shipmentId);
		warehouseInventoryManager.updateInventory(shipment);
	}

	@RequestMapping(value = { "manual-inventory-movement" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	void initInventoryMovement(@PathVariable String org,
							   @RequestParam Long productId,
							   @RequestParam Long warehouseId,
							   @RequestParam Integer delta) {
		Product product = productManager.findById(productId);
		Warehouse warehouse = warehouseManager.getWarehouseById(warehouseId);

		WarehouseInventoryMovement movement = new WarehouseInventoryMovement();
		movement.setProduct(product);
		movement.setWarehouse(warehouse);
		movement.setReason(WarehouseInventoryMovement.Reason.ASN_RECEIVE);
		movement.setDelta(delta);
		movement.setWarehouseEventId("manual_" + product.getId() + "_" + String.valueOf(System.currentTimeMillis()));
		warehouseInventoryMovementDao.save(movement);

		try {
			warehouseInventoryManager.updateInventory(movement);
		} catch (DatabaseUpdateException due) {
			throw new RuntimeException(due);
		}
	}

	@RequestMapping(value = { "apply-inventory-movement" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void applyInventoryMovement(@PathVariable String org, @RequestParam Long movementId) {
		WarehouseInventoryMovement movement = warehouseInventoryMovementDao.findById(movementId);
		try {
			warehouseInventoryManager.updateInventory(movement);
		} catch (DatabaseUpdateException due) {
			throw new RuntimeException(due);
		}
	}

	@RequestMapping(value = { "clear-blocked-order-cache" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void clearBlockedOrderCache() {
		blockedOrderCache.clearAll();
	}

	@RequestMapping(value = { "get-blocked-orders" }, method = RequestMethod.GET)
	@ResponseBody
	public Set<String> getBlockedOrders() {
		return blockedOrderCache.getGlobalOrderSet();
	}

	@RequestMapping(value = { "get-blocked-orders-on-sku" }, method = RequestMethod.GET, params = { "skuId"})
	@ResponseBody
	public Set<String> getBlockedOrdersOnSku(@PathVariable String org, @RequestParam Long skuId) {
		return blockedOrderCache.getSkuOrderSet(skuId);
	}

	@RequestMapping(value = { "contains-blocked-order" }, method = RequestMethod.GET, params = { "orderId"})
	@ResponseBody
	public boolean containsBlockedOrder(@PathVariable String org, @RequestParam Long orderId) {
		return blockedOrderCache.contains(orderId);
	}

	@RequestMapping(value="customs-value", method = RequestMethod.GET, params = { "itemId"})
	@ResponseBody
	public List<String> testCustomsValueOfLineItem(
			@PathVariable String org, @RequestParam Long itemId) {
		VictoryLineItem victoryLineItem = cartLineItemManager.findById(itemId);
		CommercialInvoiceCalculator commercialInvoiceCalculator = new CommercialInvoiceCalculator();
		Map<Product, BigDecimal> customsValueMap = commercialInvoiceCalculator.getCustomsValueInUSD(victoryLineItem);
		List<String> customsValues = Lists.newArrayList();
		for(Product product : victoryLineItem.getProductsInKit()) {
			customsValues.add(product.getGlobalSKU() + "-" + customsValueMap.get(product));
		}
		return customsValues;
	}

	@RequestMapping(value = { "sync-return-events" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void syncReturnEvents(@PathVariable String org, @RequestParam String serviceProviderName) {
		ServiceProvider serviceProvider = ServiceProvider.valueOf(serviceProviderName);
		returnManager.syncReturnEvents(serviceProvider);
	}

	@RequestMapping(value = { "sync-warehouse-without-movements" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String syncWarehousesWithoutMovements(@PathVariable String org) {
		warehouseInventoryMovementManager.syncWarehousesWithoutMovements();
		return "OK";
	}

	@RequestMapping(value = "sendMissingShipmentsReport", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void sendMissingShipmentReport(@PathVariable String org, @RequestParam int idRangeForScanningMissingShipments) {
		MissingShipmentsReport report = duplicateShipmentDetector.createMissingShipmentsReport(
				idRangeForScanningMissingShipments);
		duplicateShipmentDetector.emailReport(report);
	}

	@RequestMapping(value = { "voidShipment" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void voidShipment(@PathVariable String org, @RequestParam Long shipmentId) throws Exception {
		fulfillmentJobManager.voidShipmentInWarehouse(shipmentId);
	}

	@RequestMapping(value = { "voidShipmentsInUnkownErrorHold" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void voidUnknownErrorHoldShipments(@PathVariable String org) throws Exception {
		//We use this end point to manually force all the shipments in UNKNOWN_ERROR_HOLD status to be voided
		//so that the line items in them can be retried
		shipmentJobHelper.voidFailedShipments(ShipmentStatus.UNKNOWN_ERROR_HOLD);
	}

	@RequestMapping(value = { "voidFailedPaymentShipments" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void voidFailedPaymentShipments(@PathVariable String org) throws Exception {
		//We use this end point to manually force all the shipments in FAILED_PAYMENT status to be voided
		//so that the line items in them can be retried
		shipmentJobHelper.voidFailedShipments(ShipmentStatus.FAILED_PAYMENT);
	}

	@RequestMapping(value = { "recoverEdiOrders" }, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void recoverCorruptedEdiOrders(@PathVariable String org) throws Exception {
		shipmentJobHelper.recoverCorruptedEdiOrders();
	}

	@RequestMapping(value = { "getExternalUpdateShipmentIds" }, method = RequestMethod.POST)
	@ResponseBody
	public List<Long> getExternalUpdateShipmentIds(@PathVariable String org) throws Exception {
		return shipmentManager.getExternalUpdateShipmentIds();
	}

	@RequestMapping(value = { "updateUsePoolInventory"}, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String updateUsePoolInventoryForProducts(@RequestParam String targetSubdomain,
													@RequestParam boolean usePoolInventory) {
		int numberOfProductsUpdated = productManager.updateUsePoolInventory(targetSubdomain, usePoolInventory);
		return String.format("Updated %s products!", numberOfProductsUpdated);
	}

	@RequestMapping(value="verify-address", method= RequestMethod.POST)
	@ResponseBody
	public AddressVerificationDTO validateAddress(@RequestBody MailAddress address) {
		return addressManager.verifyAddress(address);
	}

	@Transactional
	@RequestMapping(value = { "auditWarehousePoolInventories" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	public void auditWarehousePoolInventoriesAndSendEmail(@RequestParam Long productId) {
		Product product = productManager.findById(productId);
		for (WarehouseInventory warehouseInventory : product.getWarehouseInventories()) {
			warehousePoolInventoryAuditor.applyAuditToWarehousePoolInventory(
					product.getStockKeepingUnitId(),
					warehouseInventory.getWarehouse().getId()
			);
		}
	}

	@RequestMapping(value = { "auditAllWarehousePoolInventories" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	public void auditWarehousePoolInventoriesAndSendEmail(@RequestParam String email) {
		warehousePoolInventoryAuditor.auditAllWarehousePoolInventoriesAndEmailResults(email);
	}

	@RequestMapping(value = { "auditWarehousePoolInventoriesAndSendEmail" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	public void auditWarehousePoolInventoriesAndSendEmail(@RequestParam String orgs, @RequestParam String email) {
		String[] orgArray = orgs.split(",");
		List<String> orgList = Lists.newArrayList();
		for(String org : orgArray) {
			orgList.add(org);
		}
		warehousePoolInventoryAuditor.auditWarehousePoolInventoriesAndEmailResults(orgList, email);
	}

	@RequestMapping(value = { "migrateInventoryOnHoldToPoolInventory" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	public void migrateInventoryOnHoldToPoolInventory() {
		productManager.migrateInventoryOnHoldToPoolInventory();
	}

	@RequestMapping(value = { "migrateDropshipInventoryToPoolInventory" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	public void migrateDropshipInventoryToPoolInventory() {
		productManager.migrateDropshipInventoryToPoolInventory();
	}

	@RequestMapping(value = { "auditInventoryLineItems" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	public void auditInventoryLineItemsAndSendEmail(@RequestParam String email) {
		inventoryLineItemAuditor.auditInventoryLineItemsAndSendEmail(email);
	}

	@RequestMapping(value = { "createInventoryLineItem" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String createInventoryLineItem(@RequestParam String orderId, @RequestParam String orderLineItemId,
	                                    @RequestParam Long stockKeepingUnitId, @RequestParam String poolName,
	                                    @RequestParam int units) {
		try {
			inventoryLineItemManager.createInventoryLineItem(
					new RequestLineItem(stockKeepingUnitId, units, orderLineItemId), orderId, poolName);
		} catch (InventoryLineItemException ilie) {
			return "createInventoryLineItem failed";
		}
		return "createInventoryLineItem succeeded";
	}

	@RequestMapping(value = { "createInventoryLineItems" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String createInventoryLineItems(@RequestParam String vliIdString) {
		List<Long> vliIds = Lists.newArrayList(vliIdString.split(",")).stream()
				.map(vli -> parseLong(vli))
				.collect(toList());
		StringBuilder failedIlis = new StringBuilder().append("Failed to create the following InventoryLineItems:\n");
		for (Long vliId : vliIds) {
			ownTxWrapper.inItsOwnTx(s -> {
				List<InventoryLineItem> inventoryLineItems = inventoryLineItemAuditor.getInventoryLineItemsFromVli(vliId);
				for (InventoryLineItem ili : inventoryLineItems) {
					try {
						inventoryLineItemManager.createInventoryLineItem(
								new RequestLineItem(ili.getStockKeepingUnitId(), ili.getUnits(), ili.getOrderLineItemId()),
								ili.getOrderId(), ili.getPoolName());
					} catch (InventoryLineItemException ilie) {
						failedIlis.append(ili.toString()).append("\n");
					}
				}
			});
		}
		return failedIlis.toString();
	}

	@RequestMapping(value = { "removeInventoryLineItem" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String removeInventoryLineItem(@RequestParam String orderId, @RequestParam String orderLineItemId,
	                                    @RequestParam Long stockKeepingUnitId, @RequestParam String poolName,
	                                    @RequestParam int units) {
		try {
			inventoryLineItemManager.removeInventoryLineItem(
					new RequestLineItem(stockKeepingUnitId, units, orderLineItemId), orderId, poolName);
		} catch (InventoryLineItemException ilie) {
			return "removeInventoryLineItem failed";
		}
		return "removeInventoryLineItem succeeded";
	}

	@RequestMapping(value = { "removeInventoryLineItems" }, method = RequestMethod.POST )
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String removeInventoryLineItems(@RequestParam String vliIdString) {
		List<Long> vliIds = Lists.newArrayList(vliIdString.split(",")).stream()
				.map(vli -> parseLong(vli))
				.collect(toList());
		StringBuilder failedIlis = new StringBuilder().append("Failed to delete the following InventoryLineItems:\n");
		for (Long vliId : vliIds) {
			ownTxWrapper.inItsOwnTx(s -> {
				List<InventoryLineItem> inventoryLineItems = inventoryLineItemAuditor.getInventoryLineItemsFromVli(vliId);
				for (InventoryLineItem ili : inventoryLineItems) {
					try {
						inventoryLineItemManager.removeInventoryLineItem(
								new RequestLineItem(ili.getStockKeepingUnitId(), ili.getUnits(), ili.getOrderLineItemId()),
								ili.getOrderId(), ili.getPoolName());
					} catch (InventoryLineItemException ilie) {
						failedIlis.append(ili.toString()).append("\n");
					}
				}
			});
		}
		return failedIlis.toString();
	}
}
