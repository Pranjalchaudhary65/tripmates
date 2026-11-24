package com.tripmate.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/** Everything the trip screen shows, in one call. */
public record TripDetailResponse(

        TripSummaryResponse trip,

        String description,

        @Schema(description = "How far around the destination the trip roams; 0 for one place")
        BigDecimal radiusKm,

        Instant createdAt) {
}
