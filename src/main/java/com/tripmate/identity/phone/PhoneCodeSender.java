package com.tripmate.identity.phone;

/**
 * Delivers a phone verification code. Chosen by
 * {@code tripmate.identity.phone.sms-provider}; a real SMS gateway is a new
 * implementation registered under its own value, not a change to the callers.
 * Called after the transaction that issued the code has committed, on a
 * background thread.
 */
public interface PhoneCodeSender {

    void send(PhoneCodeIssuedEvent message);
}
