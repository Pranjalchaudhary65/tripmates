package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record PhoneConfirmRequest(

        @Schema(description = "The verificationId returned when the code was sent")
        @NotNull
        UUID verificationId,

        @Schema(example = "482913", description = "The six-digit code sent to the phone")
        @NotNull @Pattern(regexp = "\\d{6}", message = "must be 6 digits")
        String code) {

    @Override
    public String toString() {
        return "PhoneConfirmRequest[]";
    }
}
