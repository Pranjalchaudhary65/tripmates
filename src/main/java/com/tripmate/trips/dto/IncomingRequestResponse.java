package com.tripmate.trips.dto;

import com.tripmate.trips.entity.MemberStatus;

import java.time.Instant;
import java.util.UUID;

/** A request on one of the caller's trips. */
public record IncomingRequestResponse(UUID id, MemberStatus status, Instant requestedAt, Instant decidedAt,
                                      TripRefResponse trip, PersonResponse requester) {
}
