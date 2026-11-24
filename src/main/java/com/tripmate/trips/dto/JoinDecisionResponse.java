package com.tripmate.trips.dto;

import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.TripStatus;

import java.time.Instant;
import java.util.UUID;

/** A request after the host's answer, with the trip's places as that answer left them. */
public record JoinDecisionResponse(UUID id, MemberStatus status, Instant decidedAt, Trip trip) {

    public record Trip(UUID id, TripStatus status, int totalSlots, int filledSlots) {
    }
}
