package com.tripmate.trust.service;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.TrustScoreAccounts;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.service.TravelHistory;
import com.tripmate.trips.service.TripCalendar;
import com.tripmate.trust.repository.TrustQueries;
import com.tripmate.trust.service.TrustScoreFormula.Breakdown;
import com.tripmate.trust.service.TrustScoreFormula.Inputs;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Computes scores off the request path and shows each user their own.
 *
 * <p>A recompute reads every input afresh (tier and vouches from identity,
 * finished trips from trips, visible reviews and recorded events from this
 * module), so it never depends on which events happened to arrive first, and
 * running it twice gives the same answer. It writes the snapshot and the
 * {@code users.trust_score} cache in one transaction, under the queue row's
 * lock.
 *
 * <p>A score is only ever shown to the person it belongs to (DESIGN.md §3:
 * the trust score is never returned to another user); other people meet it
 * only as a {@code min_trust_score} filter.
 */
@Service
@EnableConfigurationProperties(TrustProperties.class)
public class TrustScoreService {

    static final String READ_BUCKET = "trust-read";

    private final TrustQueries queries;

    private final TrustScoreAccounts accounts;

    private final AccountService accountService;

    private final TravelHistory travelHistory;

    private final RateLimiter rateLimiter;

    private final TrustProperties properties;

    private final JsonMapper json;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public TrustScoreService(TrustQueries queries, TrustScoreAccounts accounts, AccountService accountService,
                             TravelHistory travelHistory, RateLimiter rateLimiter, TrustProperties properties,
                             JsonMapper json, PlatformTransactionManager transactionManager, Clock clock) {
        this.queries = queries;
        this.accounts = accounts;
        this.accountService = accountService;
        this.travelHistory = travelHistory;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Recomputes the longest-waiting queued score, if any.
     *
     * @return false when nothing was queued
     */
    public boolean recomputeNext() {
        return Boolean.TRUE.equals(transactions.execute(status -> queries.claimDirty().map(userId -> {
            recompute(userId);
            return true;
        }).orElse(false)));
    }

    private void recompute(UUID userId) {
        Instant now = clock.instant();
        accounts.facts(userId).ifPresent(facts -> {
            Inputs inputs = new Inputs(facts.tier(), facts.activeVouchers(), queries.visibleRatings(userId),
                    travelHistory.finishedTrips(userId, TripCalendar.today(clock)), queries.scoredInputs(userId));
            Breakdown breakdown = TrustScoreFormula.compute(inputs, now);
            queries.snapshotIfChanged(userId, breakdown.score(), json.writeValueAsString(breakdown),
                    breakdown.formulaVersion(), now);
            accounts.recordScore(userId, breakdown.score(), now);
        });
        queries.markComputed(userId, now);
    }

    /** The caller's own score and its breakdown; pending until the first computation has run. */
    public TrustScoreView mine(SessionPrincipal caller) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
        return Objects.requireNonNull(readOnly.execute(status -> {
            accountService.requireActive(caller);
            return queries.latestSnapshot(caller.userId())
                    .map(snapshot -> new TrustScoreView(snapshot.score(), snapshot.computedAt(),
                            json.readValue(snapshot.componentsJson(), Breakdown.class)))
                    .orElse(TrustScoreView.PENDING);
        }));
    }

    /**
     * @param score     null while pending
     * @param breakdown null while pending
     */
    public record TrustScoreView(BigDecimal score, Instant computedAt, Breakdown breakdown) {

        static final TrustScoreView PENDING = new TrustScoreView(null, null, null);
    }
}
