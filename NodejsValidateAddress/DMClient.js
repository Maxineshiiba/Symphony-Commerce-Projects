/**
 * DM Fulfillment SOAP API client
 *
 * Consumes and emits Symphony API model objects (ShipmentStatus, InventoryDetail, etc.).
 */
var warehouseLib = require('../../node_modules/warehouse-libs/index');
var Shipment = warehouseLib.models.Shipment;
var ShipmentStatus = warehouseLib.models.ShipmentStatus;
var ShippedPackage = warehouseLib.models.ShippedPackage;
var InventoryDetail = warehouseLib.models.InventoryDetail;
var PartialAsn = warehouseLib.models.PartialAsn;
var ErrorCode = warehouseLib.errors.ErrorCode;
var UserSafeError = warehouseLib.errors.UserSafeError;
var errorUtils = warehouseLib.errors.errorUtils;
var wrapPossibleNetworkError = errorUtils.wrapPossibleNetworkError;
var soap = require('soap'), es6shim = require('es6-shim'), util = require('util'), moment = require('moment'), xmlescape = require('xml-escape'), handlebars = require('handlebars'), fs = require('fs'), winston = require('winston'), LRU = require('lru-cache');
var RECEIVER_ID = "064632888";
var services = {
    'purchaseOrder': {
        path: '/PurchaseOrders/PurchaseOrder.asmx',
        wsdlPath: 'warehouse-clients/dm/PurchaseOrder.wsdl'
    },
    'shipNotice': {
        path: '/ShipNotice/WebServiceShipNotice.asmx',
        wsdlPath: 'warehouse-clients/dm/ShipNotice.wsdl'
    },
    'catalogRequest': {
        path: '/CatalogRequest/CatalogRequest.asmx',
        wsdlPath: 'warehouse-clients/dm/CatalogRequest.wsdl'
    },
    'inboundOrder': {
        path: '/InboundOrder/InboundOrder.asmx',
        wsdlPath: 'warehouse-clients/dm/InboundOrder.wsdl'
    }
};
/** Defines the endpoints that can be specified in the WarehouseKey; clients are generated separately for each */
var endpoints = ['https://portal.suppliesnet.net', 'http://devportal.suppliesnet.net'];
var xmlTemplatesPath = 'warehouse-clients/dm/xmlTemplates';
var DMClient = (function () {
    function DMClient(config, whiteListOrderIdService) {
        var _this = this;
        var self = this;
        this.soapClients = {};
        this.shippingMethods = config.shippingMethodLookupService;
        this.warehouses = config.warehouseLookupService;
        this.carriers = config.carrierLookupService;
        this.logger = config.logger;
        this.xmlTemplates = {};
        this.addressValidationService = config.addressValidationService;
        this.whiteListOrderIdService = whiteListOrderIdService;
        this.inventoryCache = LRU({
            max: 5000,
            maxAge: 1000 * 60 * config.cacheDurationInMinutes
        });
        self.logger.info('Inventory cache duration is ' + config.cacheDurationInMinutes + ' minutes');
        // Load XML message templates
        fs.readdirSync(xmlTemplatesPath).forEach(function (filename) {
            _this.xmlTemplates[filename.replace(/\.hbs$/, '')] = handlebars.compile(fs.readFileSync(xmlTemplatesPath + '/' + filename).toString());
            self.logger.info('Loaded template: ' + filename.replace(/\.hbs$/, ''));
        });
        // Generate an instance of each service client for each endpoint location (test / prod)
        this.clientsLoading = 0;
        Object.keys(services).forEach(function (clientKey) {
            endpoints.forEach(function (endpointUriBase) {
                if (!self.soapClients[endpointUriBase]) {
                    self.soapClients[endpointUriBase] = {};
                }
                self.soapClients[endpointUriBase][clientKey] = null;
                var serviceCfg = services[clientKey];
                var endpointPath = endpointUriBase + serviceCfg.path;
                self.clientsLoading++;
                soap.createClient(serviceCfg.wsdlPath, {
                    endpoint: endpointPath
                }, function (err, client) {
                    if (err) {
                        throw err;
                    }
                    self.soapClients[endpointUriBase][clientKey] = client;
                    self.logger.info("Loaded DM client for endpoint: " + endpointPath);
                    client.on('soapError', self.onSoapError.bind(self));
                    client.on('request', self.onSoapRequest.bind(self));
                    client.on('response', self.onSoapResponse.bind(self));
                    if (--self.clientsLoading === 0) {
                        self.ready = true;
                    }
                });
            });
        });
    }
    DMClient.prototype.getHealth = function (callback) {
        var callbackMsg = "DM is healthy";
        callback.call(null, callbackMsg);
    };
    /**
    * Submit Shipment if orderId in whiteList table or passed address validation check
    *
    * Purpose: Address's orderID has been classified as bad but actually good will be stored in whiteList table,
    * so that when the job run next time, white listed orderIDs in the table will skip the address check and
    * proceed to submitShipmentAfterAddressValidated directly. Otherwise, shipment will go through address check
    *
    * @param {shipment} The desired shipment
    * @param {auth} The WarehouseCredentials
    * @param callback
    */
    DMClient.prototype.submitShipment = function (shipment, auth, callback) {
        var _this = this;
        var self = this;
        this.whiteListOrderIdService.containsOrderId(shipment.reference2, function (hasOrderId) {
            if (hasOrderId) {
                self.submitShipmentAfterAddressValidated(shipment, auth, callback);
            }
            else {
                // Validate address and reject immediately if validation fails; otherwise send to DM   
                _this.addressValidationService.validateAddress(shipment.shippingAddress, function (err, addressValidationResult) {
                    try {
                        if (err) {
                            return callback.call(self, wrapPossibleNetworkError(err));
                        }
                        if (!addressValidationResult.isValid) {
                            self.logger.info('Rejecting shipment due to address validation failure: ' + addressValidationResult.message);
                            return callback.call(self, new UserSafeError(addressValidationResult.message, ErrorCode.BAD_SHIPPING_ADDRESS_ERR));
                        }
                        shipment.shippingAddress = addressValidationResult.validatedAddress;
                        self.submitShipmentAfterAddressValidated(shipment, auth, callback);
                    }
                    catch (e) {
                        return callback.call(self, e);
                    }
                });
            }
        });
    };
    DMClient.prototype.submitShipmentAfterAddressValidated = function (shipment, auth, callback) {
        var self = this;
        var requestBody = null;
        var soapMethod = this.getSoapClient(auth, "purchaseOrder").PurchaseOrder.PurchaseOrderSoap.PlaceOrder3PF;
        var cacheKeyPrefix = this.getInventoryCacheKeyPrefix(auth, shipment.warehouse);
        try {
            requestBody = this.getDMPlaceOrderBody(shipment, auth);
        }
        catch (e) {
            return callback.call(self, e);
        }
        soapMethod.call(this, requestBody, function (err, responseBody, responseXml) {
            try {
                // Clear any cached inventory for the ordered SKUs
                shipment.lineItems.forEach(function (lineItem) {
                    self.inventoryCache.del(cacheKeyPrefix + lineItem.sku);
                });
                if (err) {
                    return callback.call(self, wrapPossibleNetworkError(err));
                }
                var acks = responseBody.PlaceOrder3PFResult.PurchaseOrderAcknowledgments, ack = acks.PurchaseOrderAcknowledgment;
                // Errors node can occur at one of two levels
                var errors = (ack && ack.Errors) || (acks && acks.Errors);
                if (errors) {
                    err = self.convertPlaceOrderError(errors.Error);
                    return callback.call(self, err);
                }
                // TODO check whether backordered quantity is nonzero, promote to INVENTORY_NA_ERR(?)
                return callback.call(self, err, { warehouseShipmentId: ack.OrderNumber });
            }
            catch (e) {
                return callback.call(self, e);
            }
        });
    };
    DMClient.prototype.getDMPlaceOrderBody = function (shipment, auth) {
        var self = this;
        var shippingInfo = {
            ImmediateOrderShipperID: parseInt(this.shippingMethods.getProviderCode(shipment.shippingMethod), 10),
            ForceDC: parseInt(this.warehouses.getProviderCode(shipment.warehouse), 10)
        };
        if (shipment.thirdPartyAccountNumber) {
            shippingInfo.FreightAccountNum = shipment.thirdPartyAccountNumber;
        }
        return {
            // NOTE I don't know why their service requires nested PurchaseOrder nodes, but it does
            PurchaseOrders: {
                PurchaseOrders: {
                    attributes: {
                        TestIndicator: (this.isTestEndpoint(auth) ? "T" : "P"),
                        SenderID: auth.clientRef,
                        ReceiverID: RECEIVER_ID
                    },
                    PurchaseOrder: [{
                            OrderType: 'Dealer DropShip',
                            CustomerPONumber: shipment.purchaseOrder,
                            DealerPONumber: shipment.symphonyShipmentId,
                            ShipTo: this.getDMAddress(shipment.shippingAddress),
                            Comment: shipment.notes,
                            PurchaseOrderLines: {
                                PurchaseOrderLine: shipment.lineItems.map(this.getDMPurchaseOrderLine)
                            },
                            ShippingInformation: shippingInfo,
                            AdditionalInformation: {
                                Data1: shipment.reference1 || '',
                                Data2: shipment.reference2 || '',
                                XMLData: { '$xml': '<Notes><![CDATA[' + xmlescape(shipment.notes) + ']]></Notes>' }
                            }
                        }]
                }
            }
        };
    };
    DMClient.prototype.cancelShipment = function (orderNumber, auth, callback) {
        var self = this;
        var cancelShipmentSoapMethod = null;
        var cancelShipmentBody = null;
        try {
            cancelShipmentBody = this.getCancelShipmentBody(orderNumber, auth);
            cancelShipmentSoapMethod = this.getSoapClient(auth, "purchaseOrder").PurchaseOrder.PurchaseOrderSoap.VoidOrder;
        }
        catch (e) {
            return callback.call(self, e);
        }
        cancelShipmentSoapMethod.call(this, cancelShipmentBody, function (err, responseBody, responseXml) {
            if (err) {
                return callback.call(self, wrapPossibleNetworkError(err));
            }
            var error = null;
            //[FUL-1623]there are two types of error xml formats, the following handles both formats
            try {
                var mainBody = responseBody.VoidOrderResult.VoidPurchaseOrdersAcknowledgement.VoidPurchaseOrderAcknowledgement;
                error = self.getFirstError(mainBody);
                if (!error) {
                    error = self.getFirstError(mainBody.Shipments.Shipment);
                }
            }
            catch (e) {
                return callback.call(self, e);
            }
            if (error) {
                return callback.call(self, self.convertCancelShipmentError(error, orderNumber));
            }
            return callback.call(self, null, orderNumber);
        });
    };
    DMClient.prototype.getCancelShipmentBody = function (orderNumber, auth) {
        var data = {
            testIndicator: xmlescape((this.isTestEndpoint(auth) ? "T" : "P")),
            senderId: xmlescape(auth.clientRef),
            receiverId: xmlescape(RECEIVER_ID),
            orderNumber: xmlescape(''),
            dealerPONumber: xmlescape(orderNumber)
        };
        return {
            "$xml": this.xmlTemplates.VoidPurchaseOrder(data)
        };
    };
    DMClient.prototype.isTestEndpoint = function (auth) {
        return (auth.providerEndpointUrl.indexOf('devportal') >= 0);
    };
    DMClient.prototype.getDMAddress = function (addr) {
        var lastName = addr.lastName || '', firstName = addr.firstName || '', personName = lastName +
            (lastName.length > 0 && firstName.length > 0 ? ', ' : '')
            + firstName;
        return {
            Name: (addr.company || personName),
            Attn: (addr.company ? personName : ''),
            Address1: addr.street1,
            Address2: addr.street2 || '',
            City: addr.city,
            State: addr.state,
            ZipCode: addr.zip,
            CountryCode: addr.country
        };
    };
    DMClient.prototype.getDMPurchaseOrderLine = function (lineItem, index) {
        return {
            Rank: index,
            OEMNumber: lineItem.sku,
            ReferenceNumber: '',
            OrderQuantity: lineItem.quantity,
            Comment: lineItem.reference,
            UnitPrice: 0,
            UOM: 'EA'
        };
    };
    /** Translate an error reported by DM's PlaceOrder method into our error code */
    DMClient.prototype.convertPlaceOrderError = function (errorInfo) {
        switch (errorInfo.ErrorNumber) {
            case "505":
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.SKU_NOT_FOUND_ERR);
            case "515":
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.NO_INVENTORY_ERR);
            default:
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.UNKNOWN_ERR);
        }
    };
    // TODO it's possible that these error conversions can be combined, but I don't have enough info yet to be sure
    DMClient.prototype.convertGetOrderStatusError = function (errorInfo, orderNumber) {
        switch (errorInfo.ErrorNumber) {
            case "510":
            /* falls through */
            case "10":
                return new UserSafeError('Cannot find shipment: '
                    + (errorInfo.ErrorPONumber || orderNumber || ''), ErrorCode.SHIPMENT_NOT_FOUND_ERR);
            default:
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.UNKNOWN_ERR);
        }
    };
    DMClient.prototype.convertCancelShipmentError = function (errorInfo, orderNumber) {
        switch (errorInfo.ErrorNumber) {
            case "3":
            case "4":
                return new UserSafeError('Cannot find shipment: '
                    + (orderNumber || ''), ErrorCode.SHIPMENT_NOT_FOUND_ERR);
            default:
                return new UserSafeError('Cannot cancel Shipment: '
                    + (orderNumber || ''), ErrorCode.CANNOT_CANCEL);
        }
    };
    DMClient.prototype.convertSubmitAsnError = function (errorInfo) {
        switch (errorInfo.ErrorNumber) {
            case "6":
                var badSkuRegex = /Item: ([^\[]*) \[Unable to find item\]/g;
                var match = badSkuRegex.exec(errorInfo.ErrorMessage);
                var badSkus = [];
                while (match) {
                    badSkus.push(match[1]);
                    match = badSkuRegex.exec(errorInfo.ErrorMessage);
                }
                if (badSkus.length > 0) {
                    return new UserSafeError('Cannot find product: ' + badSkus.join(', '), ErrorCode.SKU_NOT_FOUND_ERR);
                }
                else {
                    return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.UNKNOWN_ERR);
                }
                break;
            default:
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.UNKNOWN_ERR);
        }
    };
    DMClient.prototype.convertGetAsnError = function (errorInfo, symphonyAsnId) {
        switch (errorInfo.ErrorNumber) {
            case "5":
                return new UserSafeError('Cannot find ASN: '
                    + (symphonyAsnId || ''), ErrorCode.ASN_NOT_FOUND_ERR);
            default:
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.UNKNOWN_ERR);
        }
    };
    DMClient.prototype.convertGetInventoryError = function (errorInfo) {
        switch (errorInfo.ErrorNumber) {
            default:
                return new UserSafeError(errorInfo.ErrorDescription, ErrorCode.UNKNOWN_ERR);
        }
    };
    /** Extract first Error node from a DM Errors collection, if any, supporting single object or array */
    DMClient.prototype.getFirstError = function (node) {
        if (node && node.Errors) {
            var firstError = node.Errors.Error;
            if (Array.isArray(firstError)) {
                firstError = firstError[0];
            }
            return firstError || null;
        }
        return null;
    };
    DMClient.prototype.getShipmentDetail = function (orderNumber, auth, callback) {
        var self = this;
        var ackRequestBody = null;
        var statusRequestBody = null;
        var getAckSoapMethod = null;
        var getStatusSoapMethod = null;
        try {
            ackRequestBody = this.getDMOrderAckRequestBody([orderNumber], auth);
            statusRequestBody = this.getDMShipNoticeRequestBody([orderNumber], auth);
            getAckSoapMethod = this.getSoapClient(auth, "purchaseOrder").PurchaseOrder.PurchaseOrderSoap.RetrievePurchaseOrderAcknowledgment3PF;
            getStatusSoapMethod = this.getSoapClient(auth, "shipNotice").ShipNotice.ShipNoticeSoap.RequestShipmentNoticeXML;
        }
        catch (e) {
            return callback.call(self, e);
        }
        getStatusSoapMethod.call(this, statusRequestBody, function (err, statusResponseBody) {
            try {
                if (err) {
                    return callback.call(self, wrapPossibleNetworkError(err));
                }
                var firstError = self.getFirstError(statusResponseBody.RequestShipmentNoticeXMLResult.Shipments);
                if (firstError) {
                    return callback.call(self, self.convertGetOrderStatusError(firstError, orderNumber));
                }
                var dmStatus = statusResponseBody.RequestShipmentNoticeXMLResult.Shipments.Shipment;
                if (Array.isArray(dmStatus)) {
                    dmStatus = dmStatus[0];
                }
                getAckSoapMethod.call(this, ackRequestBody, function (err, ackResponseBody) {
                    try {
                        if (err) {
                            return callback.call(self, wrapPossibleNetworkError(err));
                        }
                        var acks = ackResponseBody.RetrievePurchaseOrderAcknowledgment3PFResult.PurchaseOrderAcknowledgments;
                        var firstError = self.getFirstError(acks);
                        if (firstError) {
                            return callback.call(self, self.convertGetOrderStatusError(firstError, orderNumber));
                        }
                        var orderAck = acks.PurchaseOrderAcknowledgment;
                        var shipment = self.getShipmentFromDMAck(orderAck, dmStatus);
                        return callback.call(self, err, shipment);
                    }
                    catch (e) {
                        return callback.call(self, e);
                    }
                });
            }
            catch (e) {
                return callback.call(self, e);
            }
        });
    };
    DMClient.prototype.getShipmentStatus = function (orderNumbers, auth, callback) {
        // TODO DM only supports at most 20 orders at a time - need to batch into sets of 20
        var self = this;
        var statusRequestBody = null;
        var getStatusSoapMethod = null;
        try {
            statusRequestBody = this.getDMShipNoticeRequestBody(orderNumbers, auth);
            getStatusSoapMethod = this.getSoapClient(auth, 'shipNotice').ShipNotice.ShipNoticeSoap.RequestShipmentNoticeXML;
        }
        catch (e) {
            return callback.call(self, e);
        }
        getStatusSoapMethod.call(this, statusRequestBody, function (err, responseBody, responseXml) {
            try {
                if (err) {
                    return callback.call(self, wrapPossibleNetworkError(err));
                }
                var firstError = self.getFirstError(responseBody.RequestShipmentNoticeXMLResult.Shipments);
                if (firstError) {
                    return callback.call(self, self.convertGetOrderStatusError(firstError));
                }
                var orderStatusList = [], dmStatusList = responseBody.RequestShipmentNoticeXMLResult.Shipments.Shipment;
                if (dmStatusList) {
                    // Because we don't have a schema in their WSDL, arrays with a single item are not
                    // recognized as arrays by node-soap
                    if (!Array.isArray(dmStatusList)) {
                        dmStatusList = [dmStatusList];
                    }
                    var dmShipmentsBySymphonyId = {};
                    dmStatusList.forEach(function (dmShipment) {
                        var existingDMShipment = dmShipmentsBySymphonyId[dmShipment.PONumber];
                        if (!existingDMShipment
                            || Number(dmShipment.ShipmentIdentification) > Number(existingDMShipment.ShipmentIdentification)) {
                            orderStatusList.push(self.getShipmentStatusFromDMStatus(dmShipment));
                            dmShipmentsBySymphonyId[dmShipment.PONumber] = dmShipment;
                        }
                    });
                    orderStatusList = Object.keys(dmShipmentsBySymphonyId).map(function (dmId) { return self.getShipmentStatusFromDMStatus(dmShipmentsBySymphonyId[dmId]); });
                }
                callback.call(self, err, orderStatusList);
            }
            catch (e) {
                return callback.call(self, e);
            }
        });
    };
    DMClient.prototype.getDMShipNoticeRequestBody = function (orderNumbers, auth) {
        // DM requires that the XML namespace prefix be "dmi", so
        // we can't rely on node-soap's default JSON->XML formatting.  $xml is a special key that
        // dumps raw XML into the message.
        // We use Handlebars to insert data into XML templates (see the xmlTemplates directory)
        var self = this;
        var data = {
            isa: xmlescape(auth.clientRef),
            orderNumbers: orderNumbers.map(xmlescape)
        };
        return {
            "$xml": this.xmlTemplates.ShipNoticeRequestNode(data)
        };
    };
    DMClient.prototype.getDMOrderAckRequestBody = function (orderNumbers, auth) {
        // See rationale for $xml on getDMShipNoticeRequestBody
        var self = this;
        var data = {
            isa: xmlescape(auth.clientRef),
            orderNumbersList: orderNumbers.map(xmlescape).join(',')
        };
        return {
            "$xml": this.xmlTemplates.PurchaseOrderRequest(data)
        };
    };
    DMClient.prototype.getShipmentFromDMAck = function (dmOrderAck, dmOrderStatus) {
        var lineItems = dmOrderAck.PurchaseOrderLines.PurchaseOrderLine;
        if (!Array.isArray(lineItems)) {
            lineItems = [lineItems];
        }
        var name = (dmOrderAck.ShipTo.Attn || dmOrderAck.ShipTo.Name).split(', ');
        var lastName = name[0];
        var firstName = name[1] || '';
        return new Shipment({
            symphonyShipmentId: dmOrderAck.DealerPONumber,
            purchaseOrder: dmOrderAck.CustomerPONumber,
            reference1: (dmOrderAck.AdditionalInformation && dmOrderAck.AdditionalInformation.Data1) || '',
            reference2: (dmOrderAck.AdditionalInformation && dmOrderAck.AdditionalInformation.Data2) || '',
            notes: '',
            shippingAddress: {
                company: (dmOrderAck.ShipTo.Attn ? dmOrderAck.ShipTo.Name : ''),
                firstName: firstName,
                lastName: lastName,
                street1: dmOrderAck.ShipTo.Address1,
                city: dmOrderAck.ShipTo.City,
                state: dmOrderAck.ShipTo.State,
                zip: dmOrderAck.ShipTo.ZipCode,
                country: '' // FIXME not provided by DM
            },
            shippingMethod: this.shippingMethods.getSymphonyCode(dmOrderStatus.ShipViaID),
            lineItems: lineItems.map(function (item) { return ({
                sku: item.OEMNumber,
                quantity: parseInt(item.OrderQuantity, 10)
            }); }),
            warehouseInstructions: '',
            warehouse: this.warehouses.getSymphonyCode('1') // FIXME
        });
    };
    DMClient.prototype.getShipmentStatusFromDMStatus = function (dmShipmentStatus) {
        var _this = this;
        // NOTE an empty ShippedDate element in the XML is being translated as {}, see:
        // https://github.com/vpulim/node-soap/issues/213
        var hasShippedDate = dmShipmentStatus.ShippedDate && typeof dmShipmentStatus.ShippedDate === 'string';
        var status = (hasShippedDate ? 'SHIPPED' : 'PROCESSING');
        var dmPackages = [];
        if (dmShipmentStatus.Packages && dmShipmentStatus.Packages.Package) {
            var dmPackages = dmShipmentStatus.Packages.Package;
            if (!Array.isArray(dmPackages)) {
                dmPackages = [dmPackages];
            }
        }
        return new ShipmentStatus({
            symphonyShipmentId: dmShipmentStatus.PONumber,
            status: status,
            shippedPackages: dmPackages.map(function (dmPackage) { return _this.getPackageStatus(dmPackage, dmShipmentStatus); })
        });
    };
    DMClient.prototype.getPackageStatus = function (dmPackage, dmShipmentStatus) {
        var items = (dmPackage.Items && dmPackage.Items.Item) || [];
        if (!Array.isArray(items)) {
            items = [items];
        }
        return new ShippedPackage({
            shippingMethod: this.shippingMethods.getSymphonyCode(dmShipmentStatus.ShipViaID) || "OTHER.CARRIER",
            trackingNumber: dmPackage.CarrierReferenceNumber,
            /**
             * DM reports ShippedDate in Central time with no time component
             * A time in the middle of the day was chosen to moot any Daylight Savings Time issues
             */
            shipTime: moment(dmShipmentStatus.ShippedDate + '10:00 -0700', 'YYYYMMDDHH:mm Z').utc().toJSON(),
            // costInUSD: Number(dmPackage.Cost),  // FIXME not provided
            weightInLb: Number(dmPackage.PackageWeight),
            contents: items.map(function (dmItem) { return ({
                sku: dmItem.ProductIDBuyer,
                quantity: parseInt(dmItem.QuantityShipped, 10)
            }); })
        });
    };
    DMClient.prototype.getDMGetInventoryRequestBody = function (skus, auth) {
        // See rationale for $xml on getDMShipNoticeRequestBody
        var self = this;
        var data = {
            isa: xmlescape(auth.clientRef),
            skus: skus.map(xmlescape)
        };
        return {
            InputRequestNode: {
                "$xml": this.xmlTemplates.ItemInformation(data)
            }
        };
    };
    DMClient.prototype.getInventoryCacheKeyPrefix = function (auth, warehouse) {
        return auth.clientRef + '-' + warehouse + '-';
    };
    DMClient.prototype.getInventory = function (warehouse, skus, auth, callback) {
        var _this = this;
        var self = this;
        var inventories = [], foundSkus = {}, uncachedSkus = [];
        var cacheKeyPrefix = this.getInventoryCacheKeyPrefix(auth, warehouse);
        // Check the cache first
        skus.forEach(function (sku) {
            var cacheKey = cacheKeyPrefix + sku;
            var cached = _this.inventoryCache.get(cacheKey);
            if (cached) {
                inventories.push(cached);
                foundSkus[sku] = true;
            }
            else {
                uncachedSkus.push(sku);
            }
        });
        if (uncachedSkus.length === 0) {
            return callback.call(self, null, inventories);
        }
        var soapClient = this.getSoapClient(auth, "catalogRequest");
        var dmWarehouseId, requestBody;
        try {
            dmWarehouseId = this.warehouses.getAlternativeProviderCode(warehouse).toLowerCase();
            requestBody = this.getDMGetInventoryRequestBody(uncachedSkus, auth);
        }
        catch (e) {
            return callback.call(self, e);
        }
        var soapMethod = soapClient.CatalogRequest.CatalogRequestSoap.RequestInfo;
        soapMethod.call(this, requestBody, function (err, responseBody, responseXml) {
            try {
                if (err) {
                    return callback.call(self, wrapPossibleNetworkError(err));
                }
                var firstError = self.getFirstError(responseBody.RequestInfoResult.ItemInformation);
                if (firstError) {
                    return callback.call(self, self.convertGetInventoryError(firstError));
                }
                var items = responseBody.RequestInfoResult.ItemInformation.Items.Item;
                // Because they don't have a schema in their WSDL, arrays with a single item are not
                // recognized as arrays by node-soap
                if (!Array.isArray(items)) {
                    items = [items];
                }
                items.forEach(function (inventoryItem) {
                    var sku = inventoryItem.attributes.OEMNumber;
                    /** Find the PLWarehouseInventory for the warehouse we're interested in */
                    var availabilities = inventoryItem.Availability;
                    if (availabilities) {
                        if (!Array.isArray(availabilities)) {
                            availabilities = [availabilities];
                        }
                        var thisWarehouseAvailability = availabilities.find(function (avail) {
                            return avail.attributes
                                && avail.attributes.DC.toLowerCase() === dmWarehouseId;
                        });
                        if (thisWarehouseAvailability) {
                            foundSkus[sku] = true;
                            var inventory = new InventoryDetail({
                                sku: sku,
                                warehouse: warehouse,
                                inventoryOnHand: parseInt(thisWarehouseAvailability.$value, 10)
                            });
                            inventories.push(inventory);
                            var cacheKey = cacheKeyPrefix + sku;
                            self.inventoryCache.set(cacheKey, inventory);
                        }
                    }
                });
                var missingSkus = skus.filter(function (sku) {
                    return !foundSkus[sku];
                });
                if (missingSkus.length > 0) {
                    err = new UserSafeError('Cannot find '
                        + (missingSkus.length > 1 ? 'products' : 'product')
                        + ': ' + missingSkus.join(', ') + ' in warehouse ' + warehouse, ErrorCode.SKU_NOT_FOUND_ERR);
                }
                callback.call(self, err, inventories);
            }
            catch (e) {
                return callback.call(self, e);
            }
        });
    };
    DMClient.prototype.submitAsn = function (asn, auth, callback) {
        var self = this;
        var requestBody = null;
        try {
            requestBody = this.getDMCreateInboundOrderBody(asn, auth);
        }
        catch (e) {
            return callback.call(self, e);
        }
        var soapMethod = this.getSoapClient(auth, 'inboundOrder').InboundOrder.InboundOrderSoap.CreateInboundOrder;
        soapMethod.call(this, requestBody, function (err, responseBody, responseXml) {
            try {
                if (err) {
                    return callback.call(self, wrapPossibleNetworkError(err));
                }
                var asnResponse = responseBody.CreateInboundOrderResult.InboundOrderStatusResponse;
                var firstError = self.getFirstError(asnResponse);
                if (firstError) {
                    return callback.call(self, self.convertSubmitAsnError(firstError));
                }
                var dmAsnId = responseBody.CreateInboundOrderResult.InboundOrderStatusResponse
                    .InboundOrders.InboundOrder.InboundOrderID;
                callback.call(self, err, { warehouseAsnId: dmAsnId });
            }
            catch (e) {
                return callback.call(self, e);
            }
        });
    };
    DMClient.prototype.getDMCreateInboundOrderBody = function (asn, auth) {
        var self = this;
        var data = {
            isa: xmlescape(auth.clientRef),
            testIndicator: this.isTestEndpoint(auth) ? 'T' : 'P',
            DCID: xmlescape(this.warehouses.getProviderCode(asn.shipToWarehouse)),
            asnItems: asn.asnItems.map(function (asnItem) { return ({
                sku: xmlescape(asnItem.sku),
                quantity: asnItem.quantityExpected
            }); }),
            clientPONumber: xmlescape(asn.symphonyAsnId),
            originPONumber: xmlescape(asn.purchaseOrder || ''),
            notes: xmlescape(asn.notes || ''),
            trackingNumber: xmlescape(asn.trackingNumber || ''),
            expectedArrivalDate: moment(asn.expectedDate).format('YYYY-MM-DD'),
            orderDate: moment().format('YYYY-MM-DD')
        };
        return {
            "xmlRequest": {
                "$xml": this.xmlTemplates.InboundOrderRequests(data)
            }
        };
    };
    DMClient.prototype.getDMGetInboundOrderStatusBody = function (symphonyAsnId, auth) {
        var self = this;
        var data = {
            isa: xmlescape(auth.clientRef),
            clientPONumber: xmlescape(symphonyAsnId)
        };
        return {
            "xmlRequest": {
                "$xml": this.xmlTemplates.InboundOrderStatusRequest(data)
            }
        };
    };
    DMClient.prototype.getAsn = function (symphonyAsnId, auth, callback) {
        var self = this;
        var requestBody = null;
        try {
            requestBody = this.getDMGetInboundOrderStatusBody(symphonyAsnId, auth);
        }
        catch (e) {
            return callback.call(self, e);
        }
        var soapMethod = this.getSoapClient(auth, 'inboundOrder').InboundOrder.InboundOrderSoap.GetInboundOrderStatus;
        soapMethod.call(this, requestBody, function (err, responseBody, responseXml) {
            try {
                if (err) {
                    return callback.call(self, wrapPossibleNetworkError(err));
                }
                var asnResponse = responseBody.GetInboundOrderStatusResult.InboundOrderStatusResponse;
                var firstError = self.getFirstError(asnResponse);
                if (firstError) {
                    return callback.call(self, self.convertGetAsnError(firstError, symphonyAsnId));
                }
                var asn = self.getAsnFromDMAsn(asnResponse.InboundOrders.InboundOrder);
                callback.call(self, err, { asn: asn });
            }
            catch (e) {
                return callback.call(self, e);
            }
        });
    };
    DMClient.prototype.getAsnFromDMAsn = function (dmAsn) {
        var items = dmAsn.Items.Item;
        if (!Array.isArray(items)) {
            items = [items];
        }
        return new PartialAsn({
            symphonyAsnId: dmAsn.ClientPONumber,
            status: this.translateAsnStatus(dmAsn.OrderStatus, items),
            asnItems: items.map(function (item) {
                return {
                    sku: item.SKU,
                    quantityExpected: parseInt(item.QuantityOrdered, 10),
                    quantityReceived: (typeof item.QuantityReceived === 'string' ?
                        parseInt(item.QuantityReceived, 10) : 0),
                    quantityStocked: parseInt(item.QuantityStocked, 10)
                };
            })
        });
    };
    DMClient.prototype.translateAsnStatus = function (dmAsnStatus, dmAsnItems) {
        var anyItemReceived = dmAsnItems.some(function (item) {
            return (item.LineStatus.toLowerCase() === "closed"
                || item.QuantityReceived > 0);
        });
        switch (dmAsnStatus.toLowerCase()) {
            case "open":
                return anyItemReceived ? "RECEIVING" : "UNRECEIVED";
            case "closed":
                return "COMPLETED";
            default:
                return "UNKNOWN";
        }
    };
    DMClient.prototype.getSoapClient = function (auth, serviceName) {
        var endpointClients = this.soapClients[auth.providerEndpointUrl];
        if (!endpointClients || !endpointClients[serviceName]) {
            throw new Error('DM SOAP client not found for endpoint: ' + auth.providerEndpointUrl);
        }
        return endpointClients[serviceName];
    };
    DMClient.prototype.onSoapError = function (err) {
        this.logger.error('SOAP error', err);
    };
    DMClient.prototype.onSoapRequest = function (req) {
        this.logger.info('SOAP request', req);
    };
    DMClient.prototype.onSoapResponse = function (res) {
        this.logger.info('SOAP response', res);
    };
    return DMClient;
})();
module.exports = DMClient;
//# sourceMappingURL=DMClient.js.map