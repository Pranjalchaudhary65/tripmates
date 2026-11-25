package com.tripmate.settlement;

import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.settlement.service.SettlementOverdueJob;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the database itself holds, whatever the application does: history
 * cannot be changed or removed, and a transaction whose legs do not balance
 * cannot be committed. Also the overdue sweep that reports a ghosted
 * settlement to the trust score, once.
 */
@IntegrationTest
class LedgerIntegrityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SettlementOverdueJob overdueJob;

    @Test
    void ledgerHistoryCannotBeUpdatedDeletedOrTruncatedThroughTheAppsOwnConnection() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        crew.spend(crew.host(), 10_000, crew.everyoneIds());
        String trip = crew.tripId();

        for (String statement : List.of(
                "update ledger_entries set amount_paise = 1 where trip_id = '%s'".formatted(trip),
                "delete from ledger_entries where trip_id = '%s'".formatted(trip),
                "truncate ledger_entries cascade",
                "update ledger_transactions set amount_paise = 1 where trip_id = '%s'".formatted(trip),
                "delete from ledger_transactions where trip_id = '%s'".formatted(trip),
                "truncate ledger_transactions cascade",
                "update settlement_state_transitions set reason = 'CONFIRMED' where settlement_id in "
                        + "(select id from settlements where trip_id = '%s')".formatted(trip),
                "delete from settlement_state_transitions where settlement_id in "
                        + "(select id from settlements where trip_id = '%s')".formatted(trip),
                "truncate settlement_state_transitions cascade",
                "truncate settlement_overdue_notices")) {
            assertThatThrownBy(() -> jdbc.execute(statement)).as(statement).hasStackTraceContaining("append-only");
        }
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where trip_id = ?::uuid", Long.class,
                trip)).isEqualTo(3);
    }

    @Test
    void aTransactionWhoseLegsDoNotBalanceCannotBeCommitted() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        crew.spend(crew.host(), 10_000, crew.everyoneIds());
        String trip = crew.tripId();
        String host = crew.host().userId();
        String companion = crew.companion(0).userId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID id = jdbc.queryForObject("""
                    insert into ledger_transactions (trip_id, kind, amount_paise, description, paid_by_user_id,
                        recorded_by_user_id, idempotency_key, request_fingerprint)
                    values (?::uuid, 'EXPENSE', 500, 'Forged', ?::uuid, ?::uuid, ?, 'x') returning id""",
                    UUID.class, trip, host, host, UUID.randomUUID().toString());
            jdbc.update("insert into ledger_entries (transaction_id, trip_id, account_user_id, entry_type, "
                    + "amount_paise) values (?, ?::uuid, ?::uuid, 'DEBIT', 500)", id, trip, host);
            jdbc.update("insert into ledger_entries (transaction_id, trip_id, account_user_id, entry_type, "
                    + "amount_paise) values (?, ?::uuid, ?::uuid, 'CREDIT', 499)", id, trip, companion);
        })).hasStackTraceContaining("does not balance");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.queryForObject("""
                insert into ledger_transactions (trip_id, kind, amount_paise, description, paid_by_user_id,
                    recorded_by_user_id, idempotency_key, request_fingerprint)
                values (?::uuid, 'EXPENSE', 500, 'No legs', ?::uuid, ?::uuid, ?, 'x') returning id""",
                UUID.class, trip, host, host, UUID.randomUUID().toString())))
                .hasStackTraceContaining("does not balance");

        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where trip_id = ?::uuid",
                Long.class, trip)).isEqualTo(1);
    }

    @Test
    void aLegCannotBeFiledUnderAnotherTripThanItsTransaction() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        Crew other = Crew.locked(port, mailpit, jdbc, 1);
        crew.spend(crew.host(), 10_000, crew.everyoneIds());
        other.spend(other.host(), 10_000, other.everyoneIds());
        UUID transaction = jdbc.queryForObject("select id from ledger_transactions where trip_id = ?::uuid",
                UUID.class, crew.tripId());

        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (transaction_id, trip_id, account_user_id, "
                + "entry_type, amount_paise) values (?, ?::uuid, ?::uuid, 'DEBIT', 1)", transaction, other.tripId(),
                other.host().userId())).hasStackTraceContaining("fk_ledger_entries_transaction");
    }

    @Test
    void aGhostedSettlementIsReportedToTheTrustScoreOncePerTripAndRole() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 2);
        crew.spend(crew.host(), 90_000, crew.everyoneIds());
        String payer = crew.companion(0).userId();
        String slowPayer = crew.companion(1).userId();
        String markedPaid = crew.settlementId(crew.companion(1), crew.host());
        crew.api(crew.companion(1)).markPaid(crew.companion(1).token(), markedPaid);
        jdbc.update("update trips set start_date = current_date - 20, end_date = current_date - 15 "
                + "where id = ?::uuid", crew.tripId());
        jdbc.update("update settlements set created_at = now() - interval '10 days' where trip_id = ?::uuid",
                crew.tripId());
        jdbc.update("update settlements set marked_paid_at = now() - interval '8 days' where id = ?::uuid",
                markedPaid);

        overdueJob.sweep();
        overdueJob.sweep();

        assertThat(overdueEvents(payer)).containsExactly("PAYER");
        assertThat(overdueEvents(crew.host().userId())).as("the host never confirmed the payment sent to them")
                .containsExactly("PAYEE");
        assertThat(overdueEvents(slowPayer)).as("marking it sent is not ghosting").isEmpty();
    }

    private List<String> overdueEvents(String userId) {
        return jdbc.queryForList("select payload ->> 'role' from outbox_events where event_type = "
                + "'SETTLEMENT_OVERDUE' and aggregate_id = ?::uuid", String.class, userId);
    }
}
