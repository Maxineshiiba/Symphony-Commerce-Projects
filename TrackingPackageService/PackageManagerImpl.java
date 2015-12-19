package sneakpeeq.core.managers.FAS;

import com.google.common.base.Preconditions;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sneakpeeq.core.annotations.DataAccessContext;
import sneakpeeq.core.annotations.DatabaseGroup;
import sneakpeeq.core.daos.FAS.PackageDao;
import sneakpeeq.core.managers.PackageTrackingManager;
import sneakpeeq.core.models.FAS.Package;
import sneakpeeq.core.models.FAS.PackageDTO;

import java.util.ArrayList;
import java.util.List;

import static sneakpeeq.core.annotations.DataAccessContext.READ_ONLY;

@Service
public class PackageManagerImpl implements PackageManager {

	private final Logger logger = LoggerFactory.getLogger(PackageManagerImpl.class);

	@Autowired PackageDao packageDao;

	@Autowired private PackageTrackingManager packageTrackingManager;

	@Override
	@Transactional(readOnly = true)
	@DatabaseGroup(DataAccessContext.READ_ONLY)
	public List<Long> findIdsWithStartDateAndEndDate(DateTime startDate, DateTime endDate) {
		LocalDate windowEnd = new LocalDate(endDate);

		List<Long> packageIdList = new ArrayList<>();
		for (LocalDate windowStart = new LocalDate(startDate);
		     !windowStart.isAfter(windowEnd);
		     windowStart = windowStart.plusDays(1)) {
			packageIdList.addAll(packageDao.findIdsByCreatedDate(windowStart));
		}
		return packageIdList;
	}

	@Override
	@Transactional
	@DatabaseGroup(READ_ONLY)
	public List<Package> findPackagesByShipmentId(Long shipmentId) {
		return packageDao.findByShipmentId(shipmentId);
	}

	@Override
	@Transactional
	@DatabaseGroup(READ_ONLY)
	public List<Package> getPackageByTrackingCode(String trackingCode) {
		return packageDao.getPackageByTrackingCode(trackingCode);
	}

	@Override
	@Transactional
	public Package findById(Long packageId) {
		return packageDao.findById(packageId);
	}

	@Override
	@Transactional
	public void trackAllPackages(DateTime startDate, DateTime endDate) {
		Preconditions.checkArgument(startDate != null && endDate != null, "cant have empty startDate and endDate");
		Preconditions.checkArgument(!startDate.isAfter(endDate), "cant have startDate after endDate");

		findIdsWithStartDateAndEndDate(startDate, endDate).stream().forEach(packageId -> {
			try {
				PackageDTO packageDTO = new PackageDTO(findById(packageId));
				packageTrackingManager.createTrackingStatusIfNotExist(packageDTO);
				logger.info("create tracking status if package is not in the table");
			}catch (Exception e){
				logger.error(String.format("Failed to create TrackingStatus for package[%d]", packageId, e));
			}
		});
	}

}
