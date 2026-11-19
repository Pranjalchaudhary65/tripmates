package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The second half of a login: the challenge from the password step and a code. */
public record SecondFactorLoginRequest(

        @Schema(description = "secondFactor.challengeId from the login response")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "is not a valid challenge")
        String challengeId,

        @Schema(description = "The current 6-digit code from the authenticator app, or an unused backup code",
                example = "492039", maxLength = 32)
        @NotBlank @Size(max = 32)
        String code) {

    @Override
    public String toString() {
        return "SecondFactorLoginRequest[]";
    }
}
