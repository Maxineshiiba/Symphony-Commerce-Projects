package sneakpeeq.core.exceptions.FAS;

import com.symphonycommerce.lib.exceptions.SPException;

/**
 * We throw this exception when an attempt is made to validate a bad address (i.e. the address is not validate).
 *
 */
public class AddressVerificationException extends SPException {
    public AddressVerificationException(String message) {
        super(message);
    }
}

