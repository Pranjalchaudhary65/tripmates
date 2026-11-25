package com.tripmate.support;

import com.tripmate.platform.outbox.DeliveredEvent;
import com.tripmate.platform.outbox.OutboxHandler;
import org.springframework.boot.test.context.TestComponent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A handler for two event types only tests publish: one it records, one it
 * always fails on. Part of the shared test context, so the relay's own tests
 * run against the production relay bean without starting a context of
 * their own.
 */
@TestComponent
public class RecordingOutboxHandler implements OutboxHandler {

    public static final String RECORDED = "TEST_RECORDED";

    public static final String FAILING = "TEST_FAILING";

    private final List<UUID> delivered = new CopyOnWriteArrayList<>();

    @Override
    public Set<String> eventTypes() {
        return Set.of(RECORDED, FAILING);
    }

    @Override
    public void handle(DeliveredEvent event) {
        if (FAILING.equals(event.eventType())) {
            throw new IllegalStateException("This handler always fails on " + FAILING);
        }
        delivered.add(event.id());
    }

    /** How many times the event was delivered to this handler. */
    public long deliveries(UUID eventId) {
        return delivered.stream().filter(eventId::equals).count();
    }
}
