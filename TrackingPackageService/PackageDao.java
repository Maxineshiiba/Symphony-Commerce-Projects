package sneakpeeq.core.daos.FAS;

import org.joda.time.LocalDate;
import sneakpeeq.core.daos.GenericDao;
import sneakpeeq.core.models.FAS.Package;

import java.util.List;


public interface PackageDao extends GenericDao<Package> {
	List<Package> findByShipmentId(Long shipmentId);
	List<Package> getPackageByTrackingCode(String trackingCode);
	List<Long> findIdsByCreatedDate(LocalDate startDate);
}
