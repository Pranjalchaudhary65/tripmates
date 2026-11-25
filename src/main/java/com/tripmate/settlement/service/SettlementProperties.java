package com.tripmate.settlement.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param writeLimit       expenses, reversals and settlement actions together, per account
 * @param readLimit        the ledger and its history, per account
 * @param overdueAfter     a suggested payment left this long after the trip ended, or a sent payment left
 *                         unconfirmed this long, counts as ghosting it
 * @param upiChangeWarning a payee's UPI ID changed this recently is pointed out to the person paying
 * @param recentExpenses   how many expenses the ledger screen lists; the history pages through all of them
 * @param jobInterval      how often the overdue sweep runs
 * @param jobBatch         overdue notices one sweep records at most
 */
@ConfigurationProperties("tripmate.settlement")
public record SettlementProperties(RateLimit writeLimit, RateLimit readLimit, Duration overdueAfter,
                                   Duration upiChangeWarning, int recentExpenses, Duration jobInterval,
                                   int jobBatch) {

    public SettlementProperties {
        String prefix = "tripmate.settlement.";
        Objects.requireNonNull(writeLimit, prefix + "write-limit is required");
        Objects.requireNonNull(readLimit, prefix + "read-limit is required");
        Objects.requireNonNull(overdueAfter, prefix + "overdue-after is required");
        Objects.requireNonNull(upiChangeWarning, prefix + "upi-change-warning is required");
        Objects.requireNonNull(jobInterval, prefix + "job-interval is required");
        if (overdueAfter.toDays() < 1 || recentExpenses < 1 || recentExpenses > 200 || jobBatch < 1) {
            throw new IllegalArgumentException(prefix + "overdue-after (whole days), recent-expenses or job-batch "
                    + "out of range");
        }
    }
}
