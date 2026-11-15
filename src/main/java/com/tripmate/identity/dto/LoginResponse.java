package com.tripmate.identity.dto;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.identity.service.LoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * The credential check passed. No token or session is issued by this
 * response; the tier lets the client decide which screen comes next.
 */
public record LoginResponse(

        UUID userId,

        @Schema(description = "Highest identity verification the account has completed")
        VerificationTier verificationTier) {

    public static LoginResponse of(LoginResult result) {
        return new LoginResponse(result.userId(), result.verificationTier());
    }
}
