package com.tripmate.trips.dto;

import com.tripmate.trips.entity.TripStatus;

import java.time.LocalDate;
import java.util.UUID;

/** The trip a join request is about. */
public record TripRefResponse(UUID id, String title, String destinationName, LocalDate startDate,
                              LocalDate endDate, TripStatus status, int totalSlots, int filledSlots) {
}
