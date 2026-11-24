package com.tripmate.trips.dto;

import com.tripmate.trips.repository.DealBreakerRules;
import com.tripmate.trips.service.MatchScorer;
import com.tripmate.trips.service.MatchService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * How well the caller fits a trip, and why: the explainable half of the
 * trip page. Every label is a fixed sentence from the server; none contains
 * anything a user typed.
 */
public record MatchResponse(

        @Schema(description = "SCORED, BLOCKED (a deal-breaker rules the trip out), NEEDS_YOUR_PROFILE or "
                + "HOST_HAS_NO_PROFILE")
        MatchService.Status status,

        @Schema(description = "0-100; null unless SCORED", example = "78")
        Integer score,

        @Schema(description = "Dimensions that clearly agree, heaviest first")
        List<Reason> matches,

        @Schema(description = "Dimensions that clearly do not, heaviest first")
        List<Reason> mismatches,

        @Schema(description = "The deal-breakers that rule the trip out; empty unless BLOCKED")
        List<Blocker> blockers) {

    public record Reason(

            MatchScorer.Dimension dimension,

            @Schema(example = "You are both early birds.")
            String label) {
    }

    public record Blocker(

            DealBreakerRules.Rule rule,

            @Schema(example = "The host is travelling only with people of their own gender.")
            String label) {
    }
}
