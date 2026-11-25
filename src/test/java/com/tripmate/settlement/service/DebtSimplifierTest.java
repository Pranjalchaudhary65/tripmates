package com.tripmate.settlement.service;

import com.tripmate.settlement.service.DebtSimplifier.Transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DESIGN.md §6: conservation, correctness and the N - 1 bound, checked on
 * thousands of random trips rather than a handful of picked examples. Each
 * seed is fixed, so a failure names the exact trip that broke it.
 */
class DebtSimplifierTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Test
    void aPairSettlesInOnePayment() {
        assertThat(DebtSimplifier.simplify(Map.of(A, 600L, B, -600L))).containsExactly(new Transfer(B, A, 600));
    }

    @Test
    void aChainCollapsesToTheFewestPayments() {
        // B owes A 200 and C owes B 200: nets are A +200, B 0, C -200, so C pays A directly.
        assertThat(DebtSimplifier.simplify(Map.of(A, 200L, B, 0L, C, -200L))).containsExactly(new Transfer(C, A, 200));
    }

    @Test
    void theLargestDebtorPaysTheLargestCreditorFirst() {
        List<Transfer> plan = DebtSimplifier.simplify(Map.of(A, 900L, B, 100L, C, -700L, D, -300L));

        assertThat(plan).containsExactly(new Transfer(C, A, 700), new Transfer(D, A, 200), new Transfer(D, B, 100));
    }

    @Test
    void everyoneEvenMeansNoPayments() {
        assertThat(DebtSimplifier.simplify(Map.of(A, 0L, B, 0L))).isEmpty();
        assertThat(DebtSimplifier.simplify(Map.of())).isEmpty();
    }

    @Test
    void balancesThatDoNotSumToZeroAreRefused() {
        assertThatThrownBy(() -> DebtSimplifier.simplify(Map.of(A, 100L, B, -99L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameBalancesAlwaysGiveTheSamePlan() {
        Map<UUID, Long> ties = new LinkedHashMap<>();
        ties.put(D, -100L);
        ties.put(C, -100L);
        ties.put(B, 100L);
        ties.put(A, 100L);
        Map<UUID, Long> reordered = new LinkedHashMap<>();
        reordered.put(A, 100L);
        reordered.put(C, -100L);
        reordered.put(B, 100L);
        reordered.put(D, -100L);

        assertThat(DebtSimplifier.simplify(ties)).isEqualTo(DebtSimplifier.simplify(reordered));
    }

    /** Random expenses between 2 and 20 people, turned into nets the way the ledger does it. */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 42, 1234, 99_991, 271_828, 314_159})
    void randomTripsAlwaysSettleExactlyInAtMostNMinusOnePayments(long seed) {
        Random random = new Random(seed);
        for (int trip = 0; trip < 500; trip++) {
            int size = 2 + random.nextInt(19);
            List<UUID> people = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                people.add(new UUID(seed, (long) trip * 100 + i));
            }
            Map<UUID, Long> net = randomNets(random, people);
            assertThat(net.values().stream().mapToLong(Long::longValue).sum()).as("conservation").isZero();

            List<Transfer> plan = DebtSimplifier.simplify(net);

            long nonZero = net.values().stream().filter(balance -> balance != 0).count();
            assertThat(plan.size()).as("seed %d trip %d: at most N - 1 payments", seed, trip)
                    .isLessThanOrEqualTo((int) Math.max(0, nonZero - 1));
            Map<UUID, Long> after = new HashMap<>(net);
            Set<UUID> payers = new HashSet<>();
            Set<UUID> payees = new HashSet<>();
            for (Transfer transfer : plan) {
                assertThat(transfer.amountPaise()).isPositive();
                assertThat(transfer.from()).isNotEqualTo(transfer.to());
                assertThat(net.get(transfer.from())).as("only debtors pay").isNegative();
                assertThat(net.get(transfer.to())).as("only creditors are paid").isPositive();
                after.merge(transfer.from(), transfer.amountPaise(), Long::sum);
                after.merge(transfer.to(), -transfer.amountPaise(), Long::sum);
                payers.add(transfer.from());
                payees.add(transfer.to());
            }
            assertThat(after.values()).as("seed %d trip %d: everyone ends at zero", seed, trip)
                    .allMatch(balance -> balance == 0);
            assertThat(Collections.disjoint(payers, payees)).as("nobody both pays and is paid").isTrue();
        }
    }

    private static Map<UUID, Long> randomNets(Random random, List<UUID> people) {
        Map<UUID, Long> net = new HashMap<>();
        people.forEach(person -> net.put(person, 0L));
        int expenses = 1 + random.nextInt(15);
        for (int i = 0; i < expenses; i++) {
            UUID payer = people.get(random.nextInt(people.size()));
            List<UUID> sharers = new ArrayList<>();
            for (UUID person : people) {
                if (random.nextBoolean()) {
                    sharers.add(person);
                }
            }
            if (sharers.isEmpty()) {
                sharers.add(people.get(random.nextInt(people.size())));
            }
            long amount = 1 + random.nextLong(5_000_000);
            net.merge(payer, amount, Long::sum);
            ExpenseSplit.equal(amount, sharers).forEach((person, share) -> net.merge(person, -share, Long::sum));
        }
        return net;
    }
}
