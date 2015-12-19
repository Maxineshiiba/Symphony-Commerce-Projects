package sneakpeeq.core.entity;

import com.easypost.model.Tracker;
import com.symphonycommerce.lib.entity.shared.EntityBaseDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sneakpeeq.core.models.FAS.TrackingStatus;

import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static java.util.stream.Collectors.toList;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TrackingStatusDTO extends EntityBaseDTO{

	private static final long serialVersionUID = 3802429966418371287L;

	@Getter @Setter
	String trackerId;

	@Getter @Setter
	String packageId;

	@Getter @Setter
	String trackingNumber;

	@Getter @Setter
	BigDecimal weight;

	@Getter @Setter
	Date estimatedDeliveryDate;

	@Getter @Setter
	String carrier;

	@Getter @Setter
	Date updated;

	@Getter @Setter
	Date created;

	@Getter @Setter
	String shippingMethod;

	@Getter @Setter
	List<TrackingHistoryDTO> trackingHistories;

	@Enumerated(EnumType.STRING)
	@Getter @Setter private TrackingStatus.Status status;

	public static TrackingStatus toHibernate(TrackingStatusDTO trackingStatusDTO){
		TrackingStatus trackingStatus = new TrackingStatus();
		trackingStatus.setTrackerId(trackingStatusDTO.getTrackerId());
		trackingStatus.setPackageId(trackingStatusDTO.getPackageId());
		trackingStatus.setTrackingNumber(trackingStatusDTO.getTrackingNumber());
		trackingStatus.setWeight(trackingStatusDTO.getWeight());
		trackingStatus.setEstimatedDeliveryDate(trackingStatusDTO.getEstimatedDeliveryDate());
		trackingStatus.setCarrier(trackingStatusDTO.getCarrier());
		trackingStatus.setUpdated(trackingStatusDTO.getUpdated());
		trackingStatus.setStatus(trackingStatusDTO.getStatus());
		trackingStatus.setCreated(trackingStatusDTO.getCreated());
		trackingStatus.setShippingMethod(trackingStatusDTO.getShippingMethod());
		return trackingStatus;
	}

	public static class Mapper {
		private boolean hasHistory;

		public Mapper withHistory() {
			hasHistory = true;
			return this;
		}

		public TrackingStatusDTO mapFrom(TrackingStatus trackingStatus) {
			TrackingStatusDTO dto = new TrackingStatusDTO();
			dto.setTrackerId(trackingStatus.getTrackerId());
			dto.setPackageId(trackingStatus.getPackageId());
			dto.setTrackingNumber(trackingStatus.getTrackingNumber());
			dto.setWeight(trackingStatus.getWeight());
			dto.setEstimatedDeliveryDate(trackingStatus.getEstimatedDeliveryDate());
			dto.setCarrier(trackingStatus.getCarrier());
			dto.setUpdated(trackingStatus.getUpdated());
			dto.setStatus(trackingStatus.getStatus());
			dto.setCreated(trackingStatus.getCreated());
			dto.setShippingMethod(trackingStatus.getShippingMethod());

			if (hasHistory) {
				dto.setTrackingHistories(trackingStatus.getTrackingHistories().stream()
						.map(TrackingHistoryDTO::new).collect(toList()));
			}
			return dto;
		}

		public TrackingStatusDTO mapFrom(Tracker tracker) {
			TrackingStatusDTO trackingStatus = new TrackingStatusDTO();
			trackingStatus.setTrackingNumber(tracker.getTrackingCode());
			trackingStatus.setTrackerId(tracker.getId());
			trackingStatus.setWeight(BigDecimal.valueOf(tracker.getWeight()));
			trackingStatus.setEstimatedDeliveryDate(tracker.getEstDeliveryDate());
			trackingStatus.setCarrier(tracker.getCarrier());
			trackingStatus.setStatus(TrackingStatus.Status.valueOf((tracker.getStatus().toUpperCase())));

			if (tracker.getCarrierDetail() != null) {
				trackingStatus.setShippingMethod(tracker.getCarrierDetail().getService());
			}

			if (tracker.getTrackingDetails() != null && !tracker.getTrackingDetails().isEmpty()) {
				trackingStatus.setUpdated(
						tracker.getTrackingDetails().get(tracker.getTrackingDetails().size() - 1).getDatetime());
			}

			if (hasHistory) {
				trackingStatus.setTrackingHistories(tracker.getTrackingDetails().stream()
						.map(TrackingHistoryDTO::new)
						.collect(toList()));
			}
			return trackingStatus;
		}
	}
}
