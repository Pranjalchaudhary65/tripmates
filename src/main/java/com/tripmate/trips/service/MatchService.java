package com.tripmate.trips.service;

import com.tripmate.trips.repository.DealBreakerRules.Rule;
import com.tripmate.trips.repository.MatchCacheRepository;
import com.tripmate.trips.repository.MatchCacheRepository.CachedMatch;
import com.tripmate.trips.repository.TripQueries;
import com.tripmate.trips.repository.TripQueries.MatchTarget;
import com.tripmate.trips.service.MatchScorer.MatchResult;
import com.tripmate.trips.service.MatchScorer.Reason;
import com.tripmate.vibe.service.MatchProfiles;
import com.tripmate.vibe.service.MatchProfiles.MatchProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * How well a viewer fits a trip, with the reasons. Two halves, in order:
 *
 * <ol>
 *   <li>Deal-breakers, in SQL ({@code DealBreakerRules}): any one failing
 *       rules the trip out and no score is given. Browse never lists such a
 *       trip; the detail screen says which rule failed, worded so it gives
 *       away no more than the rule itself.</li>
 *   <li>A soft score from the configured {@link MatchScorer}, cached per
 *       viewer and trip. A cached row is used only while it was computed by
 *       this scorer from the profiles as they are now; otherwise it is
 *       recomputed and replaced.</li>
 * </ol>
 *
 * <p>Callers pass only trips the viewer does not host; there is nothing to
 * match on one's own trip. Nothing returned names the host or carries their
 * profile values beyond what the reasons say.
 */
@Service
public class MatchService {

    private static final Map<Rule, String> BLOCKER_LABELS = Map.of(
            Rule.SMOKING, "Your smoking preference and the host's rule each other out.",
            Rule.DRINKING, "Your drinking preference and the host's rule each other out.",
            Rule.MY_GENDER_PREFERENCE, "You asked to travel only with people of your own gender.",
            Rule.HOST_GENDER_PREFERENCE, "The host is travelling only with people of their own gender.",
            Rule.MY_TRUST_MINIMUM, "The host's trust score is below the minimum you set.",
            Rule.HOST_TRUST_MINIMUM, "Your trust score is below the minimum the host set.");

    private final MatchProfiles profiles;

    private final MatchScorer scorer;

    private final TripQueries queries;

    private final MatchCacheRepository cache;

    private final JsonMapper json;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public MatchService(MatchProfiles profiles, MatchScorer scorer, TripQueries queries, MatchCacheRepository cache,
                        JsonMapper json, PlatformTransactionManager transactionManager, Clock clock) {
        this.profiles = profiles;
        this.scorer = scorer;
        this.queries = queries;
        this.cache = cache;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Scores for a list of trips that has already been through the
     * deal-breakers (browse filters them in SQL). A trip that cannot be
     * scored, because either side has no vibe profile, is absent.
     */
    public Map<UUID, Integer> scores(UUID viewer, Collection<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MatchView> views = Objects.requireNonNull(transactions.execute(status -> evaluate(viewer, tripIds)));
        Map<UUID, Integer> scores = new HashMap<>();
        views.forEach((tripId, view) -> {
            if (view.score() != null) {
                scores.put(tripId, view.score());
            }
        });
        return Map.copyOf(scores);
    }

    /** The whole match for one trip: deal-breakers first, then the score and its reasons. */
    public MatchView explain(UUID viewer, UUID tripId) {
        return transactions.execute(status -> {
            Set<Rule> failed = queries.failedDealBreakers(tripId, viewer);
            if (!failed.isEmpty()) {
                return MatchView.blocked(failed.stream().sorted()
                        .map(rule -> new Blocker(rule, BLOCKER_LABELS.get(rule))).toList());
            }
            return evaluate(viewer, List.of(tripId)).getOrDefault(tripId, MatchView.NEEDS_YOUR_PROFILE);
        });
    }

    /** True when no deal-breaker on either side rules the trip out for the viewer. */
    public boolean dealBreakersAllow(UUID viewer, UUID tripId) {
        return queries.failedDealBreakers(tripId, viewer).isEmpty();
    }

    private Map<UUID, MatchView> evaluate(UUID viewer, Collection<UUID> tripIds) {
        Map<UUID, MatchTarget> targets = queries.matchTargets(tripIds);
        Set<UUID> people = new HashSet<>();
        people.add(viewer);
        targets.values().forEach(target -> people.add(target.hostUserId()));
        Map<UUID, MatchProfile> found = profiles.of(people);
        MatchProfile mine = found.get(viewer);

        Map<UUID, MatchView> views = new HashMap<>();
        if (mine == null) {
            targets.keySet().forEach(tripId -> views.put(tripId, MatchView.NEEDS_YOUR_PROFILE));
            return views;
        }
        Map<UUID, CachedMatch> cached = cache.find(viewer, targets.keySet());
        // In trip ID order, so two transactions upserting overlapping rows lock them in the same order and
        // cannot deadlock each other.
        new TreeMap<>(targets).forEach((tripId, target) -> {
            MatchProfile host = found.get(target.hostUserId());
            if (host == null) {
                views.put(tripId, MatchView.HOST_HAS_NO_PROFILE);
                return;
            }
            views.put(tripId, MatchView.scored(scoreWithCache(tripId, viewer, mine, host, target, cached.get(tripId))));
        });
        return views;
    }

    private MatchResult scoreWithCache(UUID tripId, UUID viewer, MatchProfile mine, MatchProfile host,
                                       MatchTarget target, CachedMatch cached) {
        if (cached != null && cached.isFreshFor(scorer.id(), mine.updatedAt(), host.updatedAt())) {
            MatchResult read = readCached(cached);
            if (read != null) {
                return read;
            }
        }
        MatchResult result = scorer.score(mine.dimensions(), host.dimensions(), target.budgetBand());
        cache.put(tripId, viewer, new CachedMatch(result.score(),
                json.writeValueAsString(new Explanation(result.matches(), result.mismatches())), scorer.id(),
                mine.updatedAt(), host.updatedAt()), clock.instant());
        return result;
    }

    /** Null for a row that does not read back, which is then simply recomputed. */
    private MatchResult readCached(CachedMatch cached) {
        try {
            Explanation explanation = json.readValue(cached.explanationJson(), Explanation.class);
            if (explanation.matches() == null || explanation.mismatches() == null
                    || explanation.matches().contains(null) || explanation.mismatches().contains(null)) {
                return null;
            }
            return new MatchResult(cached.score(), explanation.matches(), explanation.mismatches());
        } catch (JacksonException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** What the cache stores beside the score. */
    record Explanation(List<Reason> matches, List<Reason> mismatches) {
    }

    /** A deal-breaker that rules the trip out, worded for the viewer. */
    public record Blocker(Rule rule, String label) {
    }

    public enum Status {
        /** Both have vibe profiles and no deal-breaker stands in the way. */
        SCORED,
        /** A deal-breaker on one side or the other rules the trip out. */
        BLOCKED,
        /** The viewer has not taken the vibe quiz, so there is nothing to compare. */
        NEEDS_YOUR_PROFILE,
        /** The host has not taken the vibe quiz. */
        HOST_HAS_NO_PROFILE
    }

    /**
     * @param score null unless SCORED
     */
    public record MatchView(Status status, Integer score, List<Reason> matches, List<Reason> mismatches,
                            List<Blocker> blockers) {

        static final MatchView NEEDS_YOUR_PROFILE = new MatchView(Status.NEEDS_YOUR_PROFILE, null, List.of(),
                List.of(), List.of());

        static final MatchView HOST_HAS_NO_PROFILE = new MatchView(Status.HOST_HAS_NO_PROFILE, null, List.of(),
                List.of(), List.of());

        public MatchView {
            matches = List.copyOf(matches);
            mismatches = List.copyOf(mismatches);
            blockers = List.copyOf(blockers);
        }

        static MatchView scored(MatchResult result) {
            return new MatchView(Status.SCORED, result.score(), result.matches(), result.mismatches(), List.of());
        }

        static MatchView blocked(List<Blocker> blockers) {
            return new MatchView(Status.BLOCKED, null, List.of(), List.of(), blockers);
        }
    }
}
