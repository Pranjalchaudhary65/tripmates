package com.tripmate.trips.dto;

import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.vibe.entity.BudgetBand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/** One trip in a list, as the caller sees it. */
public record TripSummaryResponse(

        UUID id,

        String title,

        String destinationName,

        double latitude,

        double longitude,

        LocalDate startDate,

        LocalDate endDate,

        @Schema(description = "Companions wanted, not counting the host")
        int totalSlots,

        int filledSlots,

        BudgetBand budgetBand,

        TripStatus status,

        @Schema(description = "From the searched point; null when the search had none")
        Double distanceKm,

        PersonResponse host,

        boolean hostedByMe,

        @Schema(description = "The caller's own join request on this trip; null if there is none")
        MyRequest myRequest,

        @Schema(description = "Requests waiting for an answer; only for the trip's host, otherwise null")
        Integer pendingRequests) {

    public record MyRequest(UUID id, MemberStatus status) {
    }
}
