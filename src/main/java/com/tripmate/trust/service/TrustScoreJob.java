package com.tripmate.trust.service;

import com.tripmate.trust.repository.TrustQueries;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Keeps scores current, never on a request thread (DESIGN.md, Trust Score
 * Engine spec). Each run first queues scores that are a day old (inputs
 * fade) and any active account that was never scored, then recomputes up
 * to {@code job-batch} queued scores,
 * each in its own transaction under {@code FOR UPDATE SKIP LOCKED}, so
 * several instances can run it side by side.
 */
@Component
public class TrustScoreJob {

    private final TrustQueries queries;

    private final TrustScoreService scores;

    private final TrustProperties properties;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public TrustScoreJob(TrustQueries queries, TrustScoreService scores, TrustProperties properties,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.queries = queries;
        this.scores = scores;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tripmate.trust.job-interval}", initialDelayString = "${tripmate.trust.job-interval}")
    public void run() {
        sweep();
    }

    /** @return how many scores were recomputed */
    public int sweep() {
        Instant now = clock.instant();
        Objects.requireNonNull(transactions.execute(status ->
                queries.queueStaleAndMissing(now, now.minus(properties.refreshAfter()))));
        int recomputed = 0;
        while (recomputed < properties.jobBatch() && scores.recomputeNext()) {
            recomputed++;
        }
        return recomputed;
    }
}
