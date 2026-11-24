package com.tripmate.trips.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A match stopped for safety: one of the two people stepped back, or rated
 * the meet-up as uncomfortable. Carries no reason and no word of who it was,
 * so no listener can pass either on. Published inside the releasing
 * transaction.
 */
public record CompanionReleased(UUID tripId, UUID memberId, UUID hostUserId, UUID companionUserId,
                                Instant releasedAt) {
}
