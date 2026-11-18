package com.tripmate.identity.dto;

import com.tripmate.identity.service.SessionTokens;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An access token and who it is for. The same shape from login and from
 * refresh, so a reloaded page restores exactly what login gave it, in one
 * call. The refresh token is never in a body: it travels only as an
 * HttpOnly cookie that page scripts cannot read.
 */
public record AccessGrantResponse(

        @Schema(description = "Send as 'Authorization: Bearer <token>'. Keep it in memory only.")
        String accessToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(description = "Seconds until the access token expires", example = "900")
        long expiresIn,

        SessionUserResponse user) {

    private static final String BEARER = "Bearer";

    public static AccessGrantResponse of(SessionTokens session) {
        return new AccessGrantResponse(session.accessToken().value(), BEARER,
                session.accessToken().expiresInSeconds(),
                new SessionUserResponse(session.userId(), session.role(), session.verificationTier()));
    }

    @Override
    public String toString() {
        return "AccessGrantResponse[user=" + user + "]";
    }
}
