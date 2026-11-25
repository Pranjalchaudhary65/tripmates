package com.tripmate.settlement.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseSplitTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void anEqualSplitPutsTheLeftoverPaiseOnTheFirstPeopleInIdOrder() {
        assertThat(ExpenseSplit.equal(100, List.of(C, A, B))).containsExactly(
                Map.entry(A, 34L), Map.entry(B, 33L), Map.entry(C, 33L));
    }

    @Test
    void anEqualSplitAlwaysAddsUpToTheAmount() {
        Random random = new Random(7);
        for (int i = 0; i < 2_000; i++) {
            long amount = 1 + random.nextLong(ExpenseSplit.MAX_EXPENSE_PAISE);
            List<UUID> people = IntStream.range(0, 1 + random.nextInt(20)).mapToObj(n -> new UUID(7, n)).toList();
            Map<UUID, Long> shares = ExpenseSplit.equal(amount, people);

            assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(amount);
            long smallest = shares.values().stream().mapToLong(Long::longValue).min().orElseThrow();
            long largest = shares.values().stream().mapToLong(Long::longValue).max().orElseThrow();
            assertThat(largest - smallest).as("nobody pays more than a paisa over anyone else").isLessThanOrEqualTo(1);
        }
    }

    @Test
    void exactSharesMustBePositiveAndAddUp() {
        assertThat(ExpenseSplit.addsUp(1000, Map.of(A, 600L, B, 400L))).isTrue();
        assertThat(ExpenseSplit.addsUp(1000, Map.of(A, 600L, B, 399L))).isFalse();
        assertThat(ExpenseSplit.addsUp(1000, Map.of(A, 1000L, B, 0L))).isFalse();
        assertThat(ExpenseSplit.addsUp(1000, Map.of(A, 1100L, B, -100L))).isFalse();
    }

    @Test
    void amountsAreReadAsWholePaiseNeverTruncated() {
        assertThat(ExpenseSplit.paise(new BigDecimal("125050"))).hasValue(125050L);
        assertThat(ExpenseSplit.paise(new BigDecimal("125050.00"))).hasValue(125050L);
        assertThat(ExpenseSplit.paise(new BigDecimal("1250.5"))).isEmpty();
        assertThat(ExpenseSplit.paise(new BigDecimal("0"))).isEmpty();
        assertThat(ExpenseSplit.paise(new BigDecimal("-5"))).isEmpty();
        assertThat(ExpenseSplit.paise(new BigDecimal("100000001"))).isEmpty();
        assertThat(ExpenseSplit.paise(new BigDecimal("1E+3"))).hasValue(1000L);
        assertThat(ExpenseSplit.paise(null)).isEmpty();
    }
}
