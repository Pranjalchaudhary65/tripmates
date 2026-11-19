package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Re-authentication for turning 2FA off or replacing backup codes: the
 * password and a second factor, so neither a stolen session nor a stolen
 * password is enough on its own.
 */
public record TwoFactorChangeRequest(

        @Schema(example = "violet-kettle-harbour-91", maxLength = 128)
        @NotEmpty @Size(max = 128)
        String password,

        @Schema(description = "The current 6-digit code from the authenticator app, or an unused backup code. "
                + "A backup code used here is spent.", example = "492039", maxLength = 32)
        @NotBlank @Size(max = 32)
        String code) {

    @Override
    public String toString() {
        return "TwoFactorChangeRequest[]";
    }
}
