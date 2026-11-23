package com.tripmate.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record VouchRequest(

        @Schema(description = "The TripMates ID of the person to vouch for, as shown on their account")
        @NotNull
        UUID vouchedUserId) {
}
