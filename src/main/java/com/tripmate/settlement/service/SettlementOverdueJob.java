package com.tripmate.settlement.service;

import com.tripmate.settlement.repository.SettlementQueries;
import com.tripmate.settlement.repository.SettlementQueries.Overdue;
import com.tripmate.settlement.service.SettlementTrustEvents.OverdueRole;
import com.tripmate.trips.service.TripCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Notices people ghosting a settlement: a payer who never marked a suggested
 * payment as sent, {@code overdue-after} after the trip ended, and a payee
 * who never confirmed a payment marked as sent to them. Each is recorded
 * once per trip and role and published to the outbox for the trust score;
 * nothing else happens to them here.
 *
 * <p>Each notice is its own transaction, and the notice row's primary key
 * decides who records it, so several app instances can run this at once and
 * each notice is still published exactly once.
 */
@Component
public class SettlementOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementOverdueJob.class);

    private final SettlementQueries queries;

    private final SettlementTrustEvents trustEvents;

    private final SettlementProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public SettlementOverdueJob(SettlementQueries queries, SettlementTrustEvents trustEvents,
                                SettlementProperties properties, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.queries = queries;
        this.trustEvents = trustEvents;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.settlement.job-interval}",
            initialDelayString = "${tripmate.settlement.job-interval}")
    public void run() {
        sweep();
    }

    /** One pass; returns how many notices it published. */
    public int sweep() {
        Instant now = clock.instant();
        LocalDate endedBefore = TripCalendar.today(clock).minusDays(properties.overdueAfter().toDays());
        Instant cutoff = now.minus(properties.overdueAfter());
        List<Overdue> due = queries.overdue(endedBefore, cutoff, cutoff, properties.jobBatch());
        int published = 0;
        for (Overdue overdue : due) {
            try {
                Boolean recorded = transactions.execute(tx -> {
                    if (!queries.recordOverdue(overdue.tripId(), overdue.userId(), overdue.role())) {
                        return false;
                    }
                    trustEvents.settlementOverdue(overdue.userId(), overdue.tripId(),
                            OverdueRole.valueOf(overdue.role()));
                    return true;
                });
                if (Boolean.TRUE.equals(recorded)) {
                    published++;
                }
            } catch (RuntimeException e) {
                log.warn("Settlement overdue notice failed and will be retried: {}", e.getClass().getSimpleName());
            }
        }
        return published;
    }
}
