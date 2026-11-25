package com.tripmate.trust.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The caller's reviews inbox: trips still waiting for their reviews, and visible reviews about them. */
@Schema(description = "Trips to review and reviews received")
public record MyReviewsResponse(List<TripToReview> toReview, List<Received> received) {

    @Schema(description = "waiting is how many fellow travellers the caller has not reviewed yet")
    public record TripToReview(TripReviewsResponse.Trip trip, Instant closesAt, int waiting) {
    }

    public record Received(UUID tripId, String tripTitle, String destinationName, String reviewerFirstName,
                           int rating, String comment, Instant visibleAt) {
    }
}
