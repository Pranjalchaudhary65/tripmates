package com.tripmate.meetup.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Private: never shown to the other person, never part of any score.
 *
 * @param notes optional, for the caller's own record; stored encrypted
 */
public record ComfortRatingRequest(
        @Min(1) @Max(5) @Schema(description = "1 very uncomfortable to 5 very comfortable") int comfortRating,
        @Size(max = 1000) String notes) {
}
