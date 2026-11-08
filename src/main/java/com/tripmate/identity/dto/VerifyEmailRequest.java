package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record VerifyEmailRequest(

        @Schema(description = "The verificationId returned by register or resend")
        @NotNull
        UUID verificationId,

        @Schema(example = "482913", description = "The six-digit code from the email")
        @NotNull @Pattern(regexp = "\\d{6}", message = "must be 6 digits")
        String code) {

    @Override
    public String toString() {
        return "VerifyEmailRequest[]";
    }
}
