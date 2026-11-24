package com.tripmate.trips.repository;

import com.tripmate.vibe.entity.BudgetBand;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * The browse filters, already checked: a search point comes with all three
 * of its parts or none, and the date window is in order.
 *
 * @param near     null for anywhere
 * @param from     trips starting on or after it; null for no lower bound
 * @param to       trips ending on or before it; null for no upper bound
 * @param budgets  empty for every band
 */
public record TripFilter(Near near, LocalDate from, LocalDate to, Set<BudgetBand> budgets) {

    public TripFilter {
        Objects.requireNonNull(budgets, "budgets");
        budgets = Set.copyOf(budgets);
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to before from");
        }
    }

    /** A point and a radius around it, in kilometres. */
    public record Near(double latitude, double longitude, double radiusKm) {

        public Near {
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || radiusKm <= 0) {
                throw new IllegalArgumentException("Search point out of range");
            }
        }
    }
}
