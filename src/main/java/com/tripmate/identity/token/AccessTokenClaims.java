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
     * RFC 8176 authentication methods: {@code pwd} for a password login.
     * A second factor adds its own value, which is how an admin-only route
     * will tell a password-only session from a fully verified one.
     */
    public static final String AUTH_METHODS = "amr";

    static final String PASSWORD_METHOD = "pwd";

    private AccessTokenClaims() {
    }
}
