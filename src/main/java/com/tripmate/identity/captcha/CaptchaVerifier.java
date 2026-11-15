package com.tripmate.identity.captcha;

import com.tripmate.platform.web.ClientAddress;

/**
 * Checks the token a CAPTCHA widget gave the client. Login consults it only
 * after repeated failures under the typed email, so ordinary sign-ins never
 * see a challenge.
 *
 * <p>Exactly one implementation is active, chosen by
 * {@code tripmate.identity.captcha.provider}. A provider integration is a
 * new implementation registered under its own value; nothing in the login
 * flow changes.
 */
public interface CaptchaVerifier {

    /**
     * @param token  the widget's response token, or null if the client sent none
     * @param client the address the provider should see the token as coming from
     * @return true if the token proves a human solved the challenge
     */
    boolean verify(String token, ClientAddress client);
}
