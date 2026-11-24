package com.tripmate.meetup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Allowlisted venues near the searched point, nearest first, each with the start times it allows. */
public record VenueOptionsResponse(List<Option> venues) {

    public record Option(MatchResponse.Venue venue, double distanceKm,
                         @Schema(example = "Asia/Kolkata") String timeZone, List<Window> windows) {
    }

    /** Start times allowed on one local date, both ends included, on a 15-minute grid. */
    public record Window(LocalDate date, Instant earliest, Instant latest) {
    }
}
