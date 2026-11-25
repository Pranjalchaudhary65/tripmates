package com.tripmate.settlement.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Splitting an amount of paise into shares, in whole paise. Pure functions,
 * so the arithmetic is tested on its own.
 */
public final class ExpenseSplit {

    /** One expense is at most ten lakh rupees; ck_ledger_transactions_expense_cap holds the database to it. */
    public static final long MAX_EXPENSE_PAISE = 100_000_000L;

    private ExpenseSplit() {
    }

    /**
     * Equal shares. A remainder of r paise goes one paisa each to the first r
     * people in user ID order, so the shares always add up to the amount
     * exactly and the same split always lands the same way.
     */
    public static Map<UUID, Long> equal(long amountPaise, Collection<UUID> participants) {
        if (participants.isEmpty() || amountPaise <= 0) {
            throw new IllegalArgumentException("Need a positive amount and at least one person");
        }
        List<UUID> ordered = participants.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        long base = amountPaise / ordered.size();
        long remainder = amountPaise % ordered.size();
        Map<UUID, Long> shares = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            shares.put(ordered.get(i), base + (i < remainder ? 1 : 0));
        }
        return shares;
    }

    /** True when every share is positive and they add up to exactly the amount. */
    public static boolean addsUp(long amountPaise, Map<UUID, Long> shares) {
        long total = 0;
        for (long share : shares.values()) {
            if (share <= 0) {
                return false;
            }
            total = Math.addExact(total, share);
        }
        return total == amountPaise;
    }

    /**
     * A JSON number read exactly, as whole paise: empty for a fraction of a
     * paisa (12.5), a non-positive value, or more than one expense may be.
     * Read through BigDecimal so 12.5 is refused rather than quietly
     * truncated to 12.
     */
    public static Optional<Long> paise(BigDecimal value) {
        if (value == null) {
            return Optional.empty();
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0 || stripped.signum() <= 0
                || stripped.compareTo(BigDecimal.valueOf(MAX_EXPENSE_PAISE)) > 0) {
            return Optional.empty();
        }
        return Optional.of(stripped.longValueExact());
    }
}
