package com.tripmate.identity.dto;

/**
 * What a successful credential check led to. Clients must branch on this
 * and treat a value they do not know as "cannot continue here", so a new
 * step, such as a second factor, is added as a new value and a new sibling
 * field of the response, never by changing an existing field.
 */
public enum LoginStatus {

    /** A session was opened: {@code session} holds the access token and the refresh cookie is set. */
    AUTHENTICATED,

    /**
     * The password was right and the account has 2FA: {@code secondFactor}
     * holds a challenge, and no session, access token or cookie exists yet.
     * Send the challenge and a code to /auth/login/second-factor.
     */
    SECOND_FACTOR_REQUIRED
}
