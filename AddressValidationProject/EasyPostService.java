package sneakpeeq.core.service;

import com.easypost.model.Tracker;
import sneakpeeq.core.entity.ShippingMethodDTO;
import sneakpeeq.core.entity.ShippingProfileDTO;
import sneakpeeq.core.entity.TrackingStatusDTO;
import sneakpeeq.core.entity.shared.enums.ShippingCarrier;
import sneakpeeq.core.exceptions.FAS.AddressVerificationException;
import sneakpeeq.core.models.FAS.TrackingStatus;
import sneakpeeq.core.models.MailAddress;
import sneakpeeq.core.models.fulfillment.ServiceProvider;
import sneakpeeq.core.models.fulfillment.ShipmentDTO;
import sneakpeeq.core.models.fulfillment.billing.ShipEvent;
import sneakpeeq.core.util.ConcurrentCollectionException;

import java.util.List;
import java.util.Map;

import com.easypost.exception.EasyPostException;

public interface EasyPostService {

	String CREATION_EVENT_SUFFIX = ".created";

	List<ShippingMethodDTO> findAllRatesFor(ShipmentDTO dto, ShippingProfileDTO profile, String smartpostHubId)
		throws EasyPostException;

	ShippingMethodDTO cheapestShippingMethodFor(ShipmentDTO dto, ShippingProfileDTO profile)
			throws EasyPostException;

	Map<ShipmentDTO, ShipEvent> batchBuyShippingLabel(
		Map<ShipmentDTO, ShippingProfileDTO> profileMap) throws ConcurrentCollectionException;

	void refundShipment(String shipmentId) throws EasyPostException;

	ServiceProvider getServiceProvider();

	List<MailAddress> verifyAddress(MailAddress address);

	Map<ShippingCarrier, MailAddress> getAddressSuggestions(MailAddress addressToVerify) throws AddressVerificationException;

	Tracker createTracker(String trackingCode, String carrier) throws EasyPostException;

	void updateTrackerStatus(Long trackingStatusId, TrackingStatus.Status packageStatus);

	TrackingStatusDTO translateTrackingStatus(String statusString);

}