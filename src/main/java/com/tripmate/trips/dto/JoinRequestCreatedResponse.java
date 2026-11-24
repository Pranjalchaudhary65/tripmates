package com.tripmate.trips.dto;

import com.tripmate.trips.entity.MemberStatus;

import java.time.Instant;
import java.util.UUID;

public record JoinRequestCreatedResponse(UUID id, UUID tripId, MemberStatus status, Instant requestedAt) {
}
