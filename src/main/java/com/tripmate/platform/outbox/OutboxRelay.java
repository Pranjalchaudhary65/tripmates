package com.tripmate.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivers outbox events to the modules that react to them (DESIGN.md §9).
 *
 * <p>One event at a time, oldest first, each in its own transaction: the row
 * is claimed with {@code FOR UPDATE SKIP LOCKED}, so several app instances can
 * run the relay without delivering one event twice at once; every handler
 * for its type runs; then the row is marked processed. If a handler throws,
 * the whole delivery rolls back and the failure is counted in a separate
 * transaction. The event is not tried again in the same run, so one bad
 * event cannot block the queue behind it, and after {@code max-attempts}
 * failures it is marked dead and left for someone to look at. An event no
 * handler wants is simply marked processed.
 *
 * <p>The relay never changes an event's content: V20's trigger allows it to
 * set {@code processed_at} or {@code dead_at} once and to raise
 * {@code attempts}, nothing else.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String CLAIM = """
            SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, created_at
            FROM outbox_events
            WHERE processed_at IS NULL AND dead_at IS NULL AND id NOT IN (:skip)
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED""";

    private final NamedParameterJdbcTemplate jdbc;

    private final Map<String, List<OutboxHandler>> handlersByType;

    private final OutboxProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public OutboxRelay(NamedParameterJdbcTemplate jdbc, List<OutboxHandler> handlers, OutboxProperties properties,
                       PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        Map<String, List<OutboxHandler>> byType = new HashMap<>();
        for (OutboxHandler handler : handlers) {
            for (String type : handler.eventTypes()) {
                byType.computeIfAbsent(type, key -> new ArrayList<>()).add(handler);
            }
        }
        byType.replaceAll((type, list) -> List.copyOf(list));
        this.handlersByType = Map.copyOf(byType);
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.outbox.relay-interval}",
            initialDelayString = "${tripmate.outbox.relay-interval}")
    public void run() {
        drain();
    }

    /**
     * Delivers pending events until none is left that this run has not
     * already failed on, or {@code max-per-run} have been dealt with.
     *
     * @return how many events were delivered
     */
    public int drain() {
        Set<UUID> failedThisRun = new HashSet<>();
        int delivered = 0;
        for (int handled = 0; handled < properties.maxPerRun(); handled++) {
            Attempt attempt = deliverNext(failedThisRun);
            if (attempt == null) {
                break;
            }
            if (attempt.failure() == null) {
                delivered++;
            } else {
                failedThisRun.add(attempt.event().id());
                recordFailure(attempt.event(), attempt.failure());
            }
        }
        return delivered;
    }

    /** Null when nothing is waiting. */
    private Attempt deliverNext(Set<UUID> skip) {
        DeliveredEvent[] claimed = new DeliveredEvent[1];
        try {
            transactions.executeWithoutResult(status -> {
                DeliveredEvent event = claim(skip);
                if (event == null) {
                    return;
                }
                claimed[0] = event;
                for (OutboxHandler handler : handlersByType.getOrDefault(event.eventType(), List.of())) {
                    handler.handle(event);
                }
                jdbc.update("UPDATE outbox_events SET processed_at = :now WHERE id = :id",
                        new MapSqlParameterSource("id", event.id()).addValue("now", Timestamp.from(clock.instant())));
            });
        } catch (RuntimeException failure) {
            if (claimed[0] == null) {
                throw failure;
            }
            return new Attempt(claimed[0], failure);
        }
        return claimed[0] == null ? null : new Attempt(claimed[0], null);
    }

    private DeliveredEvent claim(Set<UUID> skip) {
        // NOT IN over an empty list is not valid SQL; a random ID stands in for "skip nothing".
        Set<UUID> excluded = skip.isEmpty() ? Set.of(UUID.randomUUID()) : skip;
        List<DeliveredEvent> rows = jdbc.query(CLAIM, new MapSqlParameterSource("skip", excluded),
                (rs, row) -> new DeliveredEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Counts the failure and, at the limit, marks the event dead. Only the
     * event's ID, type and the exception class are logged: a payload can name
     * people.
     */
    private void recordFailure(DeliveredEvent event, RuntimeException failure) {
        Instant now = clock.instant();
        Integer attempts = transactions.execute(status -> {
            List<Integer> updated = jdbc.queryForList("""
                    UPDATE outbox_events
                    SET attempts = attempts + 1,
                        dead_at = CASE WHEN attempts + 1 >= :max THEN CAST(:now AS timestamptz) END
                    WHERE id = :id AND processed_at IS NULL AND dead_at IS NULL
                    RETURNING attempts""", new MapSqlParameterSource("id", event.id())
                    .addValue("max", properties.maxAttempts()).addValue("now", Timestamp.from(now)), Integer.class);
            return updated.isEmpty() ? null : updated.getFirst();
        });
        if (attempts != null && attempts >= properties.maxAttempts()) {
            log.error("Outbox event {} ({}) set aside as dead after {} failed deliveries; last failure: {}",
                    event.id(), event.eventType(), attempts, failure.getClass().getName());
        } else {
            log.warn("Outbox event {} ({}) failed delivery attempt {}: {}", event.id(), event.eventType(), attempts,
                    failure.getClass().getName());
        }
    }

    private record Attempt(DeliveredEvent event, RuntimeException failure) {
    }
}
