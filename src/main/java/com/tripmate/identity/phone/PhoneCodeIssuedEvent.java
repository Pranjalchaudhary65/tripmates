package com.tripmate.identity.phone;

import java.time.Duration;

/**
 * A phone code to deliver once the issuing transaction commits.
 *
 * @param email     the account's email, used only by the mailbox sender
 * @param e164      where a real SMS gateway would send it
 */
public record PhoneCodeIssuedEvent(String email, String fullName, String e164, String code, Duration validFor) {

    /** Neither the number nor the code belongs in a log line. */
    @Override
    public String toString() {
        return "PhoneCodeIssuedEvent[validFor=" + validFor + "]";
    }
}
