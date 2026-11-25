package com.tripmate.trust.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param rating  checked to be 1-5 by the service, so an out-of-range value
 *                gets its own error code
 * @param comment optional; cleaned of invisible characters, at most 500
 */
@Schema(description = "A review of someone who travelled on the trip with you")
public record ReviewRequest(
        @NotNull @Schema(description = "Their user ID, from the trip's review screen") UUID revieweeId,
        @NotNull @Schema(description = "1 to 5 stars", example = "5") Integer rating,
        @Schema(description = "Optional, up to 500 characters", example = "Easy to travel with, always on time")
        String comment) {
}
