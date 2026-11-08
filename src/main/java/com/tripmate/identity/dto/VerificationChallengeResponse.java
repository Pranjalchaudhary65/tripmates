package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Identical in shape and wording whether or not the address was already
 * registered. The ID is always a random UUID, real or decoy.
 */
public record VerificationChallengeResponse(

        @Schema(description = "Send this with the emailed code to verify-email, or to resend for a new code")
        UUID verificationId,

        String message) {

    public static final String MESSAGE =
            "If this email address can be used, a verification code has been sent to it.";

    public static VerificationChallengeResponse of(UUID verificationId) {
        return new VerificationChallengeResponse(verificationId, MESSAGE);
    }
}
