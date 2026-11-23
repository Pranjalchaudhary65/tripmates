package com.tripmate.platform.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Records an event in outbox_events. Requires the caller's transaction, so
 * the event commits or rolls back with the change it reports: a module never
 * calls another one synchronously to react, it leaves a row here for the
 * relay to pick up.
 */
@Component
public class OutboxPublisher {

    private final OutboxEventRepository events;

    private final JsonMapper json;

    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param payload plain values only (IDs, enum names, numbers); it is
     *                serialised as a JSON object
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(String aggregateType, UUID aggregateId, String eventType, Map<String, ?> payload) {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        return events.save(OutboxEvent.create(aggregateType, aggregateId, eventType,
                json.writeValueAsString(payload), clock.instant()));
    }
}
