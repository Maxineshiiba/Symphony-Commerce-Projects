package sneakpeeq.core.managers.FAS;

import com.easypost.exception.EasyPostException;
import com.easypost.model.Tracker;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sneakpeeq.core.daos.TrackingStatusDao;
import sneakpeeq.core.entity.TrackingHistoryDTO;
import sneakpeeq.core.entity.TrackingStatusDTO;
import sneakpeeq.core.managers.PackageTrackingManager;
import sneakpeeq.core.managers.TransactionManager;
import sneakpeeq.core.models.FAS.PackageDTO;
import sneakpeeq.core.models.FAS.TrackingHistory;
import sneakpeeq.core.models.FAS.TrackingStatus;
import sneakpeeq.core.service.EasyPostService;

import java.util.Date;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

/**
 * This class is designed to
 * 1. send package information to EasyPost
 * 2. start tracking a package
 * 3. store updated package tracking information into TrackingStatus and TrackingHistory tables
 */
@Service
public final class PackageTrackingManagerImpl implements PackageTrackingManager {

	private final Logger logger = LoggerFactory.getLogger(PackageTrackingManager.class);

	@Autowired
	TrackingStatusDao trackingStatusDao;

	@Autowired
	TransactionManager transactionManager;

	@Autowired
	EasyPostService easyPostService;

	/**
	 * Updates a TrackingStatus with updated information from a TrackingStatusDTO with a trackingStatusId
	 * @param trackingStatusId trackingStatusId stored in TrackingStatus table
	 * @param updatedTrackingStatus TrackingStatusDTO that stores updated TrackingStatus
	 */
	@Override
	@Transactional
    public void applyUpdate(Long trackingStatusId, TrackingStatusDTO updatedTrackingStatus) {
		Preconditions.checkArgument(trackingStatusId != null, "Cannot update TrackingStatus without trackingStatusId");
		Preconditions.checkArgument(updatedTrackingStatus != null, "Cannot update TrackingStatus without TrackingStatusDTO");

		TrackingStatus trackingStatus = trackingStatusDao.findById(trackingStatusId);

		if (!canAcceptUpdate(trackingStatus.getStatus())) {
			throw new IllegalStateException("Cannot update an package in following states: delivered,cancelled, return to sender, failure");
		}

		logger.info(String.format("Attempting to update tracker with latest TrackingStatus[%d]", trackingStatusId));

		trackingStatus.setUpdated(updatedTrackingStatus.getUpdated());
		trackingStatus.setStatus(updatedTrackingStatus.getStatus());
		trackingStatus.setTrackerId(updatedTrackingStatus.getTrackerId());
		trackingStatus.setWeight(updatedTrackingStatus.getWeight());
		trackingStatus.setCarrier(updatedTrackingStatus.getCarrier());
		trackingStatus.setCreated(updatedTrackingStatus.getCreated());
		trackingStatus.setShippingMethod(updatedTrackingStatus.getShippingMethod());
		trackingStatus.setEstimatedDeliveryDate(updatedTrackingStatus.getEstimatedDeliveryDate());

		if (updatedTrackingStatus.getPackageId() != null) {
			trackingStatus.setPackageId(updatedTrackingStatus.getPackageId());
		}

		trackingStatus.getTrackingHistories().clear();
		trackingStatus.getTrackingHistories().addAll(
				updatedTrackingStatus.getTrackingHistories()
						.stream()
						.map(historyDto -> {
							TrackingHistory history = TrackingHistoryDTO.toHibernate(historyDto);
							history.setTrackingStatus(trackingStatus);
							return history;
						})
						.collect(toList()));
		trackingStatusDao.save(trackingStatus);
	}

	private boolean canAcceptUpdate(TrackingStatus.Status trackingStatus){
		return ImmutableSet.of(
				TrackingStatus.Status.PENDING_FOR_STATUS,
				TrackingStatus.Status.DELIVERED,
				TrackingStatus.Status.CANCELLED,
				TrackingStatus.Status.RETURN_TO_SENDER,
				TrackingStatus.Status.FAILURE).contains(trackingStatus);
	}

	/**
	 * Updates a TrackingStatus with updated information from a TrackingStatusDTO with a trackerId
	 * replace old history with new record
	 */
	private void applyUpdateWithTrackerId(String trackerId, TrackingStatusDTO updatedTrackingStatus){
		Preconditions.checkArgument(trackerId != null, "Cannot update TrackingStatus without trackerId");
		Preconditions.checkArgument(updatedTrackingStatus != null, "Cannot update TrackingStatus without TrackingStatusDTO");
		applyUpdate(trackingStatusDao.findByTrackerId(trackerId).getId(), updatedTrackingStatus);
	}

	@Override
	@Transactional
	public void applyUpdateWithEventString(String easyPostEvent) {
		Preconditions.checkArgument(easyPostEvent != null, "Cannot update without an eventString");
		TrackingStatusDTO statusDTO = easyPostService.translateTrackingStatus(easyPostEvent);
		applyUpdateWithTrackerId(statusDTO.getTrackerId(), statusDTO);
	}

	@Transactional
	public TrackingStatus createTrackingStatusToStartTracking(PackageDTO packageDTO){
		Preconditions.checkArgument(packageDTO != null, "Cannot track a package without a trackingCode");
		logger.info("create a new tracking status");
		TrackingStatus trackingStatus = createTrackingStatus(packageDTO.getTrackingCode(), packageDTO.getCarrier().toString(), packageDTO.getId());
		createTrackerForPendingTrackingStatus(trackingStatus.getId());
		logger.info(String.format("created Tracker for TrackingStatus[%d]", trackingStatus.getId()));
		return trackingStatus;
	}

	private TrackingStatus createTrackingStatus(String trackingNumber, String carrier, Long packageId) {
		Preconditions.checkArgument(trackingNumber != null, "Cannot track a package without tracking status");
		TrackingStatus trackingStatus = new TrackingStatus();
		trackingStatus.setPackageId(packageId.toString());
		trackingStatus.setTrackingNumber(trackingNumber);
		trackingStatus.setStatus(TrackingStatus.Status.PENDING_FOR_STATUS);
		trackingStatus.setCarrier(carrier);
		trackingStatus.setUpdated(new Date());
		trackingStatus.setCreated(new Date());
		trackingStatusDao.save(trackingStatus);
		return  trackingStatus;
	}

	@Override
	@Async
	public Optional<TrackingStatus> createTrackingStatusIfNotExist(PackageDTO packageDTO) {
		Preconditions.checkArgument(packageDTO != null, "Cannot track a package without a trackingCode");
		Preconditions.checkArgument(packageDTO.getTrackingCode() != null, "Cannot track a package without a trackingCode");
		Preconditions.checkArgument(packageDTO.getCarrier() != null, "Cannot track a package without a carrier");

		try {
				transactionManager.inItsOwnTxWithResult(() -> {
							if (trackingStatusDao.findByPackageId(packageDTO.getId().toString()) == null) {
								return Optional.of(createTrackingStatusToStartTracking(packageDTO));
							}
							return Optional.empty();
						}
				);
		} catch (Exception e) {
			logger.error(String.format("Failed to create TrackingStatus for package[%d]", packageDTO.getId(), e));
		}
		return Optional.empty();
	}

	private void createTrackerForPendingTrackingStatus(Long trackingStatusId){
		Preconditions.checkArgument(trackingStatusId != null, "Cannot create tracker without a trackingStatusId");
		TrackingStatus trackingStatus = trackingStatusDao.findById(trackingStatusId);
		try {
			Tracker tracker = easyPostService.createTracker(trackingStatus.getTrackingNumber(), trackingStatus.getCarrier());
			logger.info(String.format("Tracker created for TrackingStatus[%d]. Applying update to TrackingStatus.",
					trackingStatus.getId()));
			applyUpdate(trackingStatus.getId(), new TrackingStatusDTO.Mapper().withHistory().mapFrom(tracker));
			logger.info(String.format("TrackingStatus[%d] has been updated.", trackingStatus.getId()));

		} catch (EasyPostException e) {
			logger.error("Could not create tracker with trackingStatusId {}", trackingStatusId);
			trackingStatus.setStatus(TrackingStatus.Status.ERROR);
			trackingStatusDao.update(trackingStatus);
		}
	}
}

