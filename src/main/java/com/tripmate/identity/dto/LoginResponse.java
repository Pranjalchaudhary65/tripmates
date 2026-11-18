package com.tripmate.identity.dto;

import com.tripmate.identity.service.LoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The credential check passed. {@code status} says what happens next;
 * today the only answer is a session.
 */
public record LoginResponse(

        @Schema(description = "Branch on this. An unrecognised value means the client cannot complete the login.")
        LoginStatus status,

        @Schema(description = "Present when status is AUTHENTICATED")
        AccessGrantResponse session) {

    public static LoginResponse of(LoginResult result) {
        return new LoginResponse(LoginStatus.AUTHENTICATED, AccessGrantResponse.of(result.session()));
    }
}
