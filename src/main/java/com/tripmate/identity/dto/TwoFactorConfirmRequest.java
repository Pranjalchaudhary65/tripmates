package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The first code the authenticator app shows for the secret from setup. */
public record TwoFactorConfirmRequest(

        @Schema(example = "492039", pattern = "^\\d{6}$")
        @NotBlank @Pattern(regexp = "\\d{6}", message = "must be the 6-digit code from your authenticator app")
        String code) {

    @Override
    public String toString() {
        return "TwoFactorConfirmRequest[]";
    }
}
