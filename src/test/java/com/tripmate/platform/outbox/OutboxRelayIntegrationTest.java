package com.tripmate.platform.outbox;

import com.tripmate.support.IntegrationTest;
import com.tripmate.support.RecordingOutboxHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay delivers each event once to the handlers for its type, keeps
 * going past an event that fails, gives up on it after max-attempts, and can
 * never change what an event says.
 */
@IntegrationTest
class OutboxRelayIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private RecordingOutboxHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void anEventIsDeliveredOnceAndMarkedProcessed() {
        UUID id = publish(RecordingOutboxHandler.RECORDED);
        UUID nobodyWantsIt = publish("TEST_UNHANDLED");

        relay.drain();
        relay.drain();

        assertThat(handler.deliveries(id)).isEqualTo(1);
        assertThat(row(id)).containsEntry("attempts", 0).containsEntry("dead_at", null);
        assertThat(row(id).get("processed_at")).isNotNull();
        assertThat(row(nobodyWantsIt).get("processed_at")).as("an event no handler wants is settled too").isNotNull();
    }

    @Test
    void aFailingEventDoesNotHoldUpTheQueueAndIsSetAsideAfterMaxAttempts() {
        UUID failing = publish(RecordingOutboxHandler.FAILING);
        UUID behindIt = publish(RecordingOutboxHandler.RECORDED);

        relay.drain();

        assertThat(handler.deliveries(behindIt)).as("the event behind a failing one still goes").isEqualTo(1);
        assertThat(row(failing)).containsEntry("attempts", 1).containsEntry("processed_at", null)
                .containsEntry("dead_at", null);

        relay.drain();
        relay.drain();
        relay.drain();

        assertThat(row(failing)).containsEntry("attempts", 3).containsEntry("processed_at", null);
        assertThat(row(failing).get("dead_at")).as("dead after max-attempts (3 in tests)").isNotNull();
    }

    @Test
    void theDatabaseRefusesAnyChangeToAnEventsContentOrItsDeliveryRecord() {
        UUID id = publish(RecordingOutboxHandler.RECORDED);
        relay.drain();

        for (String statement : List.of(
                "update outbox_events set payload = '{\"x\":2}' where id = '%s'".formatted(id),
                "update outbox_events set event_type = 'OTHER' where id = '%s'".formatted(id),
                "update outbox_events set aggregate_id = gen_random_uuid() where id = '%s'".formatted(id),
                "update outbox_events set created_at = now() where id = '%s'".formatted(id),
                "update outbox_events set processed_at = null where id = '%s'".formatted(id),
                "update outbox_events set processed_at = now() + interval '1 day' where id = '%s'".formatted(id),
                "update outbox_events set attempts = attempts - 1 where id = '%s'".formatted(id),
                "delete from outbox_events where id = '%s'".formatted(id),
                "truncate outbox_events cascade")) {
            assertThatThrownBy(() -> jdbc.execute(statement)).as(statement).hasStackTraceContaining("append-only");
        }
        assertThat(row(id).get("payload").toString()).contains("\"marker\"");
    }

    @Test
    void anEventCannotBeBothProcessedAndDead() {
        UUID id = publish("TEST_UNHANDLED_TWICE");
        jdbc.update("update outbox_events set processed_at = now() where id = ?", id);

        assertThatThrownBy(() -> jdbc.update("update outbox_events set dead_at = now() where id = ?", id))
                .hasStackTraceContaining("ck_outbox_settled_once");
    }

    private UUID publish(String type) {
        return new TransactionTemplate(transactionManager).execute(status ->
                publisher.publish("TEST", UUID.randomUUID(), type, Map.of("marker", "relay-test")).getId());
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("select processed_at, dead_at, attempts, payload from outbox_events where id = ?", id);
    }
}
