package com.tripmate.settlement.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Turns a trip's net balances into the transfers that settle them
 * (DESIGN.md §6, Debt simplification algorithm). A pure function: no
 * database, no clock, no state, so it can be checked against thousands of
 * random trips.
 *
 * <p>Greedy, largest first: the biggest debtor pays the biggest creditor as
 * much as one of them needs, which settles at least one of the two, and
 * repeats. Every step retires at least one person, and the last step retires
 * two (balances sum to zero), so N people with a non-zero balance settle in
 * at most N - 1 transfers. Nobody both pays and receives. Ties are broken by
 * user ID, so the same balances always give the same plan; the planner keeps
 * a suggestion unchanged unless the balances behind it changed.
 */
public final class DebtSimplifier {

    private static final Comparator<Balance> LARGEST_FIRST = Comparator.comparingLong(Balance::amount).reversed()
            .thenComparing(Balance::userId);

    private DebtSimplifier() {
    }

    /**
     * @param netBalances paise per person: positive is owed to them, negative
     *                    is owed by them; must sum to exactly zero
     * @return transfers from a debtor to a creditor, each a positive amount
     */
    public static List<Transfer> simplify(Map<UUID, Long> netBalances) {
        long total = 0;
        PriorityQueue<Balance> creditors = new PriorityQueue<>(LARGEST_FIRST);
        PriorityQueue<Balance> debtors = new PriorityQueue<>(LARGEST_FIRST);
        for (Map.Entry<UUID, Long> entry : netBalances.entrySet()) {
            long net = entry.getValue();
            total = Math.addExact(total, net);
            if (net > 0) {
                creditors.add(new Balance(entry.getKey(), net));
            } else if (net < 0) {
                debtors.add(new Balance(entry.getKey(), Math.negateExact(net)));
            }
        }
        if (total != 0) {
            throw new IllegalArgumentException("Net balances must sum to zero, not " + total);
        }
        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            Balance creditor = creditors.poll();
            Balance debtor = debtors.poll();
            long amount = Math.min(creditor.amount(), debtor.amount());
            transfers.add(new Transfer(debtor.userId(), creditor.userId(), amount));
            if (creditor.amount() > amount) {
                creditors.add(new Balance(creditor.userId(), creditor.amount() - amount));
            }
            if (debtor.amount() > amount) {
                debtors.add(new Balance(debtor.userId(), debtor.amount() - amount));
            }
        }
        return List.copyOf(transfers);
    }

    /** Paise from one person to another. */
    public record Transfer(UUID from, UUID to, long amountPaise) {
    }

    private record Balance(UUID userId, long amount) {
    }
}
