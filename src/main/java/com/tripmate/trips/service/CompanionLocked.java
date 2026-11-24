package com.tripmate.trips.service;

import java.time.Instant;
import java.util.UUID;

/**
 * A companion is locked in: the public meet-up went well for both people.
 * Published inside the transaction that locks them, so listeners (the chat
 * unlocking contact details) commit with it.
 */
public record CompanionLocked(UUID tripId, UUID memberId, UUID hostUserId, UUID companionUserId, Instant lockedAt) {
}
