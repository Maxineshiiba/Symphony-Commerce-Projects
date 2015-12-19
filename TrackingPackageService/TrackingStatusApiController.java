package sneakpeeq.sharedweb.controllers;


import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import sneakpeeq.core.managers.FAS.PackageManager;
import sneakpeeq.core.managers.FAS.ShipmentManager;
import sneakpeeq.core.managers.PackageTrackingManager;
import sneakpeeq.core.models.FAS.TrackingStatus;
import sneakpeeq.core.service.EasyPostService;

@Controller
@RequestMapping("/org/{org}/api/v1/package-tracking")
public class TrackingStatusApiController extends GenericController {

	@Autowired
	public PackageTrackingManager packageTrackingManager;
	@Autowired
	public EasyPostService easyPostService;
	@Autowired
	public ShipmentManager shipmentManager;
	@Autowired
	public PackageManager packageManager;

	/**
	 * Change tracker status
	 * @param trackingStatusId
	 */
	@RequestMapping(value = "/{trackingStatusId}/tracker-status", method = RequestMethod.PUT)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changeTrackerStatus(@PathVariable Long trackingStatusId, @RequestBody String packageStatus) {
		easyPostService.updateTrackerStatus(trackingStatusId, TrackingStatus.Status.valueOf(packageStatus.toUpperCase()));
	}

	/**
	 * import all tracking numbers within date range to tracking status table and track packages
	 * @param startDate
	 * @param endDate
	 */
	@RequestMapping(value = "import-tracking-numbers", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void trackPackagesWithinDateRange(
			@RequestParam @DateTimeFormat(pattern="yyyy-MM-dd-hh:mm:ss") DateTime startDate,
			@RequestParam @DateTimeFormat(pattern="yyyy-MM-dd-hh:mm:ss") DateTime endDate) {
		packageManager.trackAllPackages(startDate, endDate);
	}

	/**
	 * endpoint take in Event/Response from EasyPost
	 * @param trackingStatusEventStr EasyPost Event String
	 */
	@RequestMapping(value = "event-taker", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void eventTaker(@RequestBody String trackingStatusEventStr) {
		packageTrackingManager.applyUpdateWithEventString(trackingStatusEventStr);
	}
}
