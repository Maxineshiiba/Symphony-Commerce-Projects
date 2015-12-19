package sneakpeeq.core.managers.FAS;

import org.joda.time.DateTime;
import sneakpeeq.core.models.FAS.Package;

import java.util.List;

public interface PackageManager {
	List<Long> findIdsWithStartDateAndEndDate(DateTime startDate, DateTime endDate);
	List<Package> findPackagesByShipmentId(Long shipmentId);
	List<Package> getPackageByTrackingCode(String trackingCode);
	Package findById(Long packageId);
	void trackAllPackages(DateTime startDate, DateTime endDate);
}
