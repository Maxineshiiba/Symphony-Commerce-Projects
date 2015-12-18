package sneakpeeq.core.managers;

import sneakpeeq.core.entity.AddressVerificationDTO;
import sneakpeeq.core.entity.shared.AddressDTO;
import sneakpeeq.core.exceptions.InvalidAddressException;
import sneakpeeq.core.models.MailAddress;
import sneakpeeq.core.models.ZipcodeData;

import java.math.BigDecimal;

public interface AddressManager {
	boolean isCountryValid(AddressDTO address);
	boolean isMilitaryAddress(AddressDTO address);
	boolean isPOBox(AddressDTO address);
	boolean isAddressValid(AddressDTO address);
    ZipcodeData getZipcodeDataFromZipcode(String zipcode);
	BigDecimal getDistanceInKm(AddressDTO from, AddressDTO to);
	BigDecimal getDistanceInMi(AddressDTO from, AddressDTO to);
	String getCountryCode(String countryName);
	void validateAddress(AddressDTO address, boolean shouldValidatePhone) throws InvalidAddressException;
	void validateCountry(AddressDTO address) throws InvalidAddressException;
	AddressVerificationDTO verifyAddress (MailAddress address);
}
