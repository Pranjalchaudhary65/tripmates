package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Re-authentication for starting setup: the account password, as at login. */
public record TwoFactorSetupRequest(

        @Schema(example = "violet-kettle-harbour-91", maxLength = 128)
        @NotEmpty @Size(max = 128)
        String password) {

    @Override
    public String toString() {
        return "TwoFactorSetupRequest[]";
    }
}
