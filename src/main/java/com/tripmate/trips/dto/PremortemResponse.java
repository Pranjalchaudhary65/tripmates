package com.tripmate.trips.dto;

import com.tripmate.trips.service.MatchScorer.Dimension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The conflict pre-mortem for a locked trip: for each other traveller, the
 * gaps between their vibe and the caller's worth settling before the trip.
 * Fixed sentences only, never a profile value.
 *
 * @param myVibeSet false until the caller has taken the vibe quiz
 */
public record PremortemResponse(UUID tripId, LocalDate startDate, boolean locked, boolean myVibeSet,
                                List<Pair> pairs) {

    /** @param vibeSet false when that traveller has not taken the quiz */
    public record Pair(String firstName, boolean vibeSet, List<Topic> topics) {
    }

    public record Topic(Dimension dimension, String gap, String question) {
    }
}
