package com.tripmate.trips.service;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.Trip;
import com.tripmate.trips.entity.TripMember;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.trips.repository.TripCards.IncomingRequest;
import com.tripmate.trips.repository.TripCards.OutgoingRequest;
import com.tripmate.trips.repository.TripMemberRepository;
import com.tripmate.trips.repository.TripQueries;
import com.tripmate.trips.repository.TripRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Join requests: a traveller asks to join someone else's trip, and the host
 * accepts or rejects.
 *
 * <p>Accepting claims a slot on the trip. The claim is guarded by the
 * trip's optimistic lock alone, not by any lock on the host's account: two
 * accepts that read the trip at the same version cannot both commit, the
 * loser is retried from a fresh read, and the retry sees the trip as it now
 * is, full or not. A trip is never overfilled, and an accept is never
 * refused while a slot is still free unless it loses every one of its
 * {@code slotClaimAttempts} tries.
 *
 * <p>A request is addressed by its own ID, never by trip and request ID
 * together, so there is no pair of IDs to mismatch. Anyone but the trip's
 * host gets 404 for it, the same as for an ID that does not exist.
 */
@Service
public class JoinRequestService {

    static final String REQUEST_BUCKET = "trip-join-request";

    static final String DECISION_BUCKET = "trip-join-decision";

    private final AccountService accounts;

    private final TripRepository trips;

    private final TripMemberRepository members;

    private final TripQueries queries;

    private final MatchService matches;

    private final RateLimiter rateLimiter;

    private final TripProperties properties;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public JoinRequestService(AccountService accounts, TripRepository trips, TripMemberRepository members,
                              TripQueries queries, MatchService matches, RateLimiter rateLimiter,
                              TripProperties properties, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.accounts = accounts;
        this.trips = trips;
        this.members = members;
        this.queries = queries;
        this.matches = matches;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Asks to join a trip. Counted and checked under the caller's user row
     * lock, so two requests sent together cannot both pass the pending cap or
     * both find no earlier request. A trip a deal-breaker on either side
     * rules out is refused, so a host's limits hold even for someone who
     * reaches the trip through a link rather than browse.
     */
    public TripMember request(SessionPrincipal caller, UUID tripId) {
        rateLimiter.consume(REQUEST_BUCKET, caller.userId().toString(), properties.joinRequestLimit());
        return transactions.execute(status -> {
            accounts.lockActive(caller);
            UUID userId = caller.userId();
            Trip trip = trips.findById(tripId).orElseThrow(JoinRequestService::notFound);
            if (trip.isHostedBy(userId)) {
                throw new ApiProblemException(TripProblem.OWN_TRIP);
            }
            if (members.findByTripIdAndUserId(tripId, userId).isPresent()) {
                throw new ApiProblemException(TripProblem.JOIN_REQUEST_EXISTS);
            }
            if (!trip.getStatus().isListed() || !queries.isActiveUser(trip.getHostUserId())) {
                throw notFound();
            }
            requireTakingCompanions(trip, TripCalendar.today(clock));
            if (!matches.dealBreakersAllow(userId, tripId)) {
                throw new ApiProblemException(TripProblem.DEAL_BREAKER_CONFLICT);
            }
            if (members.countByUserAndStatus(userId, MemberStatus.REQUESTED) >= properties.maxPendingRequests()) {
                throw new ApiProblemException(TripProblem.TOO_MANY_PENDING_REQUESTS);
            }
            return members.save(TripMember.request(tripId, userId, clock.instant()));
        });
    }

    public Decision accept(SessionPrincipal caller, UUID requestId) {
        return decide(caller, requestId, true);
    }

    public Decision reject(SessionPrincipal caller, UUID requestId) {
        return decide(caller, requestId, false);
    }

    /**
     * Runs the decision in a fresh transaction per attempt. Only an
     * optimistic lock failure is retried: it means another change to the
     * same trip or request committed first, and the next attempt reads what
     * that change left behind.
     */
    private Decision decide(SessionPrincipal caller, UUID requestId, boolean accept) {
        rateLimiter.consume(DECISION_BUCKET, caller.userId().toString(), properties.decisionLimit());
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> decideOnce(caller, requestId, accept));
            } catch (OptimisticLockingFailureException lost) {
                if (attempt >= properties.slotClaimAttempts()) {
                    throw new ApiProblemException(TripProblem.TRIP_BUSY);
                }
            }
        }
    }

    private Decision decideOnce(SessionPrincipal caller, UUID requestId, boolean accept) {
        accounts.requireActive(caller);
        TripMember member = members.findById(requestId).orElseThrow(JoinRequestService::notFound);
        Trip trip = trips.findById(member.getTripId()).orElseThrow(JoinRequestService::notFound);
        if (!trip.isHostedBy(caller.userId())) {
            throw notFound();
        }
        if (!member.isPending()) {
            throw new ApiProblemException(TripProblem.JOIN_REQUEST_DECIDED);
        }
        Instant now = clock.instant();
        if (accept) {
            if (!queries.isActiveUser(member.getUserId())) {
                throw notFound();
            }
            requireTakingCompanions(trip, TripCalendar.today(clock));
            trip.claimSlot(now);
            member.accept(now);
            trips.save(trip);
        } else {
            member.reject(now);
        }
        members.save(member);
        return new Decision(member, trip);
    }

    public Page<IncomingRequest> incoming(SessionPrincipal caller, UUID tripId, MemberStatus status,
                                          Pageable pageable) {
        consumeRead(caller);
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            return queries.incoming(caller.userId(), tripId, status, pageable);
        });
    }

    public Page<OutgoingRequest> outgoing(SessionPrincipal caller, MemberStatus status, Pageable pageable) {
        consumeRead(caller);
        return readOnly.execute(tx -> {
            accounts.requireActive(caller);
            return queries.outgoing(caller.userId(), status, pageable);
        });
    }

    /** Open, not started yet, and with a slot left. A full trip says so; anything else is closed. */
    private static void requireTakingCompanions(Trip trip, LocalDate today) {
        if (trip.hasStarted(today) || !trip.getStatus().isListed()) {
            throw new ApiProblemException(TripProblem.TRIP_CLOSED);
        }
        if (trip.getStatus() == TripStatus.FULL) {
            throw new ApiProblemException(TripProblem.TRIP_FULL);
        }
    }

    private void consumeRead(SessionPrincipal caller) {
        rateLimiter.consume(TripService.READ_BUCKET, caller.userId().toString(), properties.readLimit());
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(CommonProblem.NOT_FOUND);
    }

    /** A request after the host's decision, with the trip as that decision left it. */
    public record Decision(TripMember request, Trip trip) {
    }
}
