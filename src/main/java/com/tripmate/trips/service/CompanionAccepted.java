package com.tripmate.trips.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A host accepted a join request: the companion now has a place on the trip
 * and the next rung of the trust ladder, the masked chat, opens.
 *
 * <p>Published inside the accept's transaction. Modules downstream of trips
 * listen for it in that same transaction, so what they create commits or
 * rolls back with the accept; trips itself never calls them.
 */
public record CompanionAccepted(UUID tripId, UUID memberId, UUID hostUserId, UUID companionUserId,
                                Instant acceptedAt) {
}
