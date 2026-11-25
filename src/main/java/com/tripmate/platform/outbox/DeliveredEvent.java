package com.tripmate.platform.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * An outbox event as the relay hands it to a handler.
 *
 * @param payload   the JSON object the publisher wrote
 * @param createdAt when the change it reports committed; a handler dates its
 *                  reaction from this, not from when the relay got to it
 */
public record DeliveredEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload,
                             Instant createdAt) {
}
