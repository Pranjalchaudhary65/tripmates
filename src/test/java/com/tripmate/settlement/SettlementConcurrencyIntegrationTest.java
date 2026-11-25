package com.tripmate.settlement;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.settlement.entity.Settlement;
import com.tripmate.settlement.repository.SettlementRepository;
import com.tripmate.settlement.service.DebtSimplifier;
import com.tripmate.settlement.service.ExpenseSplit;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.Traveller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.tripmate.settlement.SettlementApi.equalSplit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DESIGN.md §6: races on one trip's ledger cannot double-write it or leave it inconsistent. */
@IntegrationTest
class SettlementConcurrencyIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettlementRepository settlements;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentConfirmationsOfOnePaymentWriteExactlyOneLedgerPair() throws Exception {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        Traveller payer = crew.companion(0);
        crew.spend(crew.host(), 80_000, crew.everyoneIds());
        String settlement = crew.settlementId(payer, crew.host());
        assertThat(crew.api(payer).markPaid(payer.token(), settlement).statusCode()).isEqualTo(200);

        List<Integer> statuses = race(8, () -> crew.api(crew.host()).confirm(crew.host().token(), settlement)
                .statusCode());

        assertThat(statuses).as("a repeated confirm is answered, not failed").containsOnly(200);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where settlement_id = ?::uuid",
                Long.class, settlement)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where t.settlement_id = ?::uuid""", Long.class, settlement)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from settlement_state_transitions where settlement_id = "
                + "?::uuid and reason = 'CONFIRMED'", Long.class, settlement)).isEqualTo(1);
    }

    @Test
    void concurrentExpensesAllLandAndTheBalancesAndPlanStayExact() throws Exception {
        Crew crew = Crew.locked(port, mailpit, jdbc, 3);
        List<Traveller> everyone = crew.everyone();
        int expenses = 12;
        List<Callable<Integer>> adds = new ArrayList<>();
        Map<String, Long> expected = new HashMap<>();
        everyone.forEach(traveller -> expected.put(traveller.userId(), 0L));
        for (int i = 0; i < expenses; i++) {
            Traveller payer = everyone.get(i % everyone.size());
            long amount = 10_001L * (i + 1);
            List<String> sharers = crew.everyoneIds();
            expected.merge(payer.userId(), amount, Long::sum);
            ExpenseSplit.equal(amount,
                            sharers.stream().map(UUID::fromString).toList())
                    .forEach((user, share) -> expected.merge(user.toString(), -share, Long::sum));
            String body = equalSplit(UUID.randomUUID(), "Round " + i, amount, sharers);
            adds.add(() -> crew.api(payer).addExpense(payer.token(), crew.tripId(), body).statusCode());
        }

        List<Integer> statuses = raceAll(adds);

        assertThat(statuses).containsOnly(201);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where trip_id = ?::uuid "
                + "and kind = 'EXPENSE'", Long.class, crew.tripId())).isEqualTo(expenses);
        String view = crew.ledgerOf(crew.host());
        Map<String, Long> balances = new HashMap<>();
        List<Map<String, Object>> members = JsonPath.read(view, "$.members");
        members.forEach(member -> balances.put((String) member.get("userId"),
                ((Number) member.get("netPaise")).longValue()));
        assertThat(balances).isEqualTo(expected);
        List<Map<String, Object>> proposed = JsonPath.read(view, "$.settlements[?(@.status == 'PROPOSED')]");
        assertThat(proposed).as("one plan, at most N - 1 payments").hasSizeLessThanOrEqualTo(everyone.size() - 1);
        Map<UUID, Long> nets = new HashMap<>();
        expected.forEach((user, net) -> nets.put(UUID.fromString(user), net));
        assertThat(proposed.stream().map(row -> new DebtSimplifier.Transfer(
                UUID.fromString(JsonPath.read(row, "$.from.userId")), UUID.fromString(JsonPath.read(row, "$.to.userId")),
                ((Number) row.get("amountPaise")).longValue())).toList())
                .containsExactlyInAnyOrderElementsOf(DebtSimplifier.simplify(nets));
        assertThat(jdbc.queryForObject("""
                select coalesce(sum(case when entry_type = 'DEBIT' then amount_paise else -amount_paise end), 0)
                from ledger_entries where trip_id = ?::uuid""", Long.class, crew.tripId())).isZero();
    }

    /**
     * The version column is real, not decoration: a change made from a stale
     * read of a settlement is refused even without the trip's lock.
     */
    @Test
    void aChangeFromAStaleReadOfASettlementIsRefusedByItsVersion() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        crew.spend(crew.host(), 10_000, crew.everyoneIds());
        UUID id = UUID.fromString(crew.settlementId(crew.companion(0), crew.host()));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Settlement stale = tx.execute(status -> settlements.findById(id).orElseThrow());
        tx.executeWithoutResult(status -> {
            Settlement fresh = settlements.findById(id).orElseThrow();
            fresh.markPaid(Instant.now());
            settlements.save(fresh);
        });

        stale.markPaid(Instant.now());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> settlements.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private static List<Integer> race(int threads, Callable<Integer> call) throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(call);
        }
        return raceAll(calls);
    }

    /** Starts every call at the same moment and waits for all of them. */
    private static List<Integer> raceAll(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
