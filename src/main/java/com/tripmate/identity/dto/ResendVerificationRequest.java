package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ResendVerificationRequest(

        @Schema(description = "The most recent verificationId this client received")
        @NotNull
        UUID verificationId) {
}
