package com.tripmate.identity.mail;

import java.time.Duration;

/**
 * Published inside the transaction that stored the code's hash; the email
 * goes out only once that transaction commits. This record is the only place
 * the plaintext code exists after generation, so {@link #toString()} leaves it
 * and the address out - an event logged by a framework or an error handler
 * must not print a live code.
 */
public record VerificationCodeIssuedEvent(String email, String fullName, String code, Duration validFor) {

    @Override
    public String toString() {
        return "VerificationCodeIssuedEvent[validFor=" + validFor + "]";
    }
}
