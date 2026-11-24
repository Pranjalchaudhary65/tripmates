package com.tripmate.trips.entity;

import com.tripmate.vibe.entity.BudgetBand;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What a host says about a trip when opening it, already checked against
 * the trip rules by the service. The constructor repeats the checks the
 * database would otherwise be the first to make.
 */
public record TripDetails(String title, String description, String destinationName, double latitude,
                          double longitude, BigDecimal radiusKm, LocalDate startDate, LocalDate endDate,
                          int totalSlots, BudgetBand budgetBand) {

    public TripDetails {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(destinationName, "destinationName");
        Objects.requireNonNull(radiusKm, "radiusKm");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(budgetBand, "budgetBand");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Coordinates out of range");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate before startDate");
        }
        if (totalSlots < 1 || totalSlots > Trip.MAX_SLOTS) {
            throw new IllegalArgumentException("totalSlots must be between 1 and " + Trip.MAX_SLOTS);
        }
    }
}
