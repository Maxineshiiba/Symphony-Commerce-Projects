package sneakpeeq.core.entity;

import lombok.Data;
import sneakpeeq.core.entity.shared.enums.ShippingCarrier;
import sneakpeeq.core.models.MailAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by guest on 9/25/15.
 */
@Data
public class AddressVerificationDTO {
    MailAddress suggestedAddress;
    private List<ShippingCarrier> verifiedCarrier = new ArrayList<ShippingCarrier>();

}
