package com.tripmate.trust.dto;

import com.tripmate.trust.service.ReviewService.PersonStatus;
import com.tripmate.trust.service.ReviewService.WindowStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One trip's review screen for one traveller: everyone else who travelled,
 * what the caller wrote about them, and what they wrote about the caller
 * once it is visible. A review still hidden by the double-blind is never in
 * this response.
 */
@Schema(description = "The review screen of one trip")
public record TripReviewsResponse(Trip trip, Window window, List<Person> people) {

    public record Trip(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate) {
    }

    @Schema(description = "Reviews open the day after the trip ends and close at closesAt; hidden reviews become "
            + "visible then")
    public record Window(WindowStatus status, LocalDate opensOn, Instant closesAt) {
    }

    @Schema(description = "at is when the caller's own review was submitted, or when theirs became visible")
    public record WrittenReview(int rating, String comment, Instant at) {
    }

    @Schema(description = "theirs is null until it is visible")
    public record Person(UUID userId, String firstName, PersonStatus status, WrittenReview mine,
                         WrittenReview theirs) {
    }
}
