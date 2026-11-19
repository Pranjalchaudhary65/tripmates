package com.tripmate.identity.token;

import com.nimbusds.jose.JOSEObjectType;

/**
 * Names shared by the code that writes access tokens and the code that
 * checks them, so the two cannot drift apart.
 */
public final class AccessTokenClaims {

    /**
     * RFC 9068 media type, required in the header. A JWT of any other type
     * signed with the same key (an ID token, a future email-link token) can
     * never be replayed as an access token.
     */
    static final JOSEObjectType TYPE = new JOSEObjectType("at+jwt");

    /** The refresh token family this token was minted from; doubles as the session ID. */
    public static final String SESSION_ID = "sid";

    /** {@code USER} or {@code ADMIN}, as in users.role. */
    public static final String ROLE = "role";

    /**
     * RFC 8176 authentication methods: {@code pwd} for a password login, and
     * {@code otp} added when the login also passed a TOTP or backup code
     * (both are one-time passwords). This is how an admin-only route tells a
     * password-only session from a fully verified one.
     */
    public static final String AUTH_METHODS = "amr";

    static final String PASSWORD_METHOD = "pwd";

    public static final String OTP_METHOD = "otp";

    /**
     * Each amr value becomes a granted authority with this prefix, so an
     * authorization rule can require {@code AMR_otp} the same way it requires
     * a role.
     */
    public static final String AUTH_METHOD_AUTHORITY_PREFIX = "AMR_";

    private AccessTokenClaims() {
    }
}
