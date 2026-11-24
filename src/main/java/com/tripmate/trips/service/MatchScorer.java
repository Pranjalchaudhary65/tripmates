package com.tripmate.trips.service;

import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.VibeDimensions;

import java.util.List;
import java.util.Objects;

/**
 * The soft half of matching: how well a viewer's vibe fits a trip and its
 * host, as a 0-100 score with the reasons behind it. Pluggable, so a model
 * could write the comparison one day; {@link RuleBasedMatchScorer} is the
 * default and needs no API key.
 *
 * <p>Deal-breakers are deliberately not part of this interface. They are
 * hard filters applied in SQL before any scorer runs, so no scorer, however
 * it is implemented, can let through a trip someone has ruled out.
 */
public interface MatchScorer {

    /**
     * Stored with every cached score. Changing how scores are computed means
     * changing this, which makes every cached row stale at once.
     */
    String id();

    /**
     * @param viewer     the person looking at the trip
     * @param host       the trip's host
     * @param tripBudget the trip's own budget band, which counts rather than the host's usual one
     */
    MatchResult score(VibeDimensions viewer, VibeDimensions host, BudgetBand tripBudget);

    /** One dimension that clearly agrees or clearly does not, worded for the viewer. */
    record Reason(Dimension dimension, String label) {

        public Reason {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * @param score      0-100
     * @param matches    dimensions that clearly agree, heaviest first
     * @param mismatches dimensions that clearly do not, heaviest first
     */
    record MatchResult(int score, List<Reason> matches, List<Reason> mismatches) {

        public MatchResult {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("score must be between 0 and 100");
            }
            matches = List.copyOf(matches);
            mismatches = List.copyOf(mismatches);
        }
    }

    /** The eight vibe dimensions, as the explanation names them. */
    enum Dimension {
        BUDGET, PACE, WAKE_TIME, PLANNING, SOCIAL_ENERGY, RISK_APPETITE, FOOD, EXPENSES
    }
}
