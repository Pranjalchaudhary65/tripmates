package com.tripmate.trips.dto;

import com.tripmate.trips.entity.MemberStatus;

import java.time.Instant;
import java.util.UUID;

/** One of the caller's own requests, with its trip and host. */
public record OutgoingRequestResponse(UUID id, MemberStatus status, Instant requestedAt, Instant decidedAt,
                                      TripRefResponse trip, PersonResponse host) {
}
