package com.tripmate.identity.dto;

import com.tripmate.identity.service.LoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The credential check passed. {@code status} says what happens next: a
 * session, or a second factor first. Exactly one of the two sibling fields is
 * set, the one the status names.
 */
public record LoginResponse(

        @Schema(description = "Branch on this. An unrecognised value means the client cannot complete the login.")
        LoginStatus status,

        @Schema(description = "Present when status is AUTHENTICATED", nullable = true)
        AccessGrantResponse session,

        @Schema(description = "Present when status is SECOND_FACTOR_REQUIRED", nullable = true)
        SecondFactorChallengeResponse secondFactor) {

    public static LoginResponse of(LoginResult result) {
        if (result.isSignedIn()) {
            return new LoginResponse(LoginStatus.AUTHENTICATED, AccessGrantResponse.of(result.session()), null);
        }
        return new LoginResponse(LoginStatus.SECOND_FACTOR_REQUIRED, null,
                SecondFactorChallengeResponse.of(result.challenge()));
    }
}
