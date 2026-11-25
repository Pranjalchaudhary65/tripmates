package com.tripmate.platform.outbox;

import java.util.Set;

/**
 * A module's reaction to events other modules left in the outbox. The relay
 * shows a handler only the event types it names, inside the transaction that
 * marks the event delivered, so a reaction and its delivery commit or roll
 * back together. A handler that throws rolls the event back for every
 * handler; it is retried on a later run, and given up after
 * {@code tripmate.outbox.max-attempts}.
 *
 * <p>An event can reach a handler again after a crash between the handler's
 * work and the commit, so a handler must be safe to run twice for one event
 * ID.
 */
public interface OutboxHandler {

    /** Exact event type names; nothing else is ever passed to {@link #handle}. */
    Set<String> eventTypes();

    void handle(DeliveredEvent event);
}
