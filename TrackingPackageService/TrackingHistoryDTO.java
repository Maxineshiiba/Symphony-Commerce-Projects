package sneakpeeq.core.entity;

import com.easypost.model.TrackingDetail;
import com.symphonycommerce.lib.entity.shared.EntityBaseDTO;
import lombok.Getter;
import lombok.Setter;
import sneakpeeq.core.models.FAS.TrackingHistory;
import sneakpeeq.core.models.FAS.TrackingStatus;

import javax.persistence.EnumType;
import javax.persistence.Enumerated;


public class TrackingHistoryDTO extends EntityBaseDTO {

	private static final long serialVersionUID = -8717844398131856879L;

	@Getter @Setter
	String message;

	@Enumerated(EnumType.STRING)
	@Getter @Setter private TrackingStatus.Status status;

	@Getter @Setter
	String currentCity;

	@Getter @Setter
	String currentState;

	@Getter @Setter
	String currentCountry;

	@Getter @Setter
	String currentZip;

	public TrackingHistoryDTO() { }

	/**
	 * Translate an EasyPost TrackingDetail into a TrackHistoryDTO
	 * @param trackingDetail an EasyPost TrackingDetail
	 * @return a TrackingHistoryDTO
	 */
	public TrackingHistoryDTO(TrackingDetail trackingDetail){
		this.setUpdatedAt(trackingDetail.getDatetime());
		this.setMessage(trackingDetail.getMessage());
		this.setStatus(TrackingStatus.Status.valueOf(trackingDetail.getStatus().toUpperCase()));
		this.setCurrentCity(trackingDetail.getTrackingLocation().getCity());
		this.setCurrentState(trackingDetail.getTrackingLocation().getState());
		this.setCurrentCountry(trackingDetail.getTrackingLocation().getCountry());
		this.setCurrentZip(trackingDetail.getTrackingLocation().getZip());
	}

	public TrackingHistoryDTO(TrackingHistory trackingHistory){
		this.setUpdatedAt(this.getUpdatedAt());
		this.setMessage(this.getMessage());
		this.setStatus(this.getStatus());
		this.setCurrentCity(this.getCurrentCity());
		this.setCurrentState(this.getCurrentState());
		this.setCurrentCountry(this.getCurrentCountry());
		this.setCurrentZip(this.getCurrentZip());
	}

	/**
	 * Convert a TrackingHistoryDTO into a TrackHistory
	 * @param  trackingHistoryDTO
	 * @return a TrackingHistory
	 */
	public static TrackingHistory toHibernate(TrackingHistoryDTO trackingHistoryDTO){
		TrackingHistory trackingHistory = new TrackingHistory();
		trackingHistory.setCreated(trackingHistoryDTO.getUpdatedAt());
		trackingHistory.setMessage(trackingHistoryDTO.getMessage());
		trackingHistory.setCurrentCity(trackingHistoryDTO.getCurrentCity());
		trackingHistory.setCurrentState(trackingHistoryDTO.getCurrentState());
		trackingHistory.setCurrentCountry(trackingHistoryDTO.getCurrentCountry());
		trackingHistory.setCurrentZip(trackingHistoryDTO.getCurrentZip());
		trackingHistory.setStatus(trackingHistoryDTO.getStatus());
		return trackingHistory;
	}
}
