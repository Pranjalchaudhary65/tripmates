package com.tripmate.trips.service;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.entity.Trip;
import com.tripmate.trips.entity.TripDetails;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.trips.repository.TripCards.TripCard;
import com.tripmate.trips.repository.TripCards.TripPage;
import com.tripmate.trips.repository.TripFilter;
import com.tripmate.trips.repository.TripQueries;
import com.tripmate.trips.repository.TripRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Opening, browsing and reading trips.
 *
 * <p>A trip is visible to its host, to anyone with a request on it, and,
 * while it is OPEN or FULL and its host is still active, to every signed-in
 * user. Anything else answers 404, the same as an ID that does not exist.
 */
@Service
@EnableConfigurationProperties(TripProperties.class)
public class TripService {

    public static final int MAX_DAYS_AHEAD = 365;

    public static final int MAX_LENGTH_DAYS = 60;

    static final String CREATE_BUCKET = "trip-create";

    static final String READ_BUCKET = "trip-read";

    private static final List<TripStatus> CURRENT = List.of(TripStatus.OPEN, TripStatus.FULL);

    private final AccountService accounts;

    private final TripRepository trips;

    private final TripQueries queries;

    private final RateLimiter rateLimiter;

    private final TripProperties properties;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public TripService(AccountService accounts, TripRepository trips, TripQueries queries, RateLimiter rateLimiter,
                       TripProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.accounts = accounts;
        this.trips = trips;
        this.queries = queries;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Opens a trip hosted by the caller. The cap on current trips is counted
     * under the caller's user row lock, so parallel requests cannot pass it.
     */
    public TripPage open(SessionPrincipal caller, TripDetails details) {
        rateLimiter.consume(CREATE_BUCKET, caller.userId().toString(), properties.createLimit());
        LocalDate today = TripCalendar.today(clock);
        requireValidDates(details.startDate(), details.endDate(), today);
        UUID tripId = transactions.execute(status -> {
            accounts.lockActive(caller);
            if (trips.countCurrentHostedBy(caller.userId(), CURRENT, today) >= properties.maxCurrentTrips()) {
                throw new ApiProblemException(TripProblem.TOO_MANY_TRIPS);
            }
            return trips.save(Trip.open(caller.userId(), details, clock.instant())).getId();
        });
        return find(caller, tripId);
    }

    public Page<TripCard> browse(SessionPrincipal caller, TripFilter filter, Pageable pageable) {
        consumeRead(caller);
        return readOnly.execute(status -> {
            accounts.requireActive(caller);
            return queries.browse(caller.userId(), filter, TripCalendar.today(clock), pageable);
        });
    }

    public Page<TripCard> hosted(SessionPrincipal caller, Pageable pageable) {
        consumeRead(caller);
        return readOnly.execute(status -> {
            accounts.requireActive(caller);
            return queries.hostedBy(caller.userId(), TripCalendar.today(clock), pageable);
        });
    }

    public TripPage detail(SessionPrincipal caller, UUID tripId) {
        consumeRead(caller);
        return find(caller, tripId);
    }

    private TripPage find(SessionPrincipal caller, UUID tripId) {
        return readOnly.execute(status -> {
            accounts.requireActive(caller);
            return queries.findPage(tripId, caller.userId())
                    .filter(TripService::isVisible)
                    .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
        });
    }

    static boolean isVisible(TripPage page) {
        TripCard card = page.card();
        return card.hostedByViewer() || card.viewerRequest() != null
                || (card.status().isListed() && page.hostActive());
    }

    /**
     * The date rules on their own, for a caller to apply before building
     * {@link TripDetails}, whose constructor refuses an end before the start
     * outright: checked here first, that request answers invalid_trip_dates
     * instead of failing as a programming error.
     */
    public void requireValidDates(LocalDate start, LocalDate end) {
        requireValidDates(start, end, TripCalendar.today(clock));
    }

    /** From today, at most a year ahead, at most 60 days long; all in IST. */
    static void requireValidDates(LocalDate start, LocalDate end, LocalDate today) {
        if (start.isBefore(today) || start.isAfter(today.plusDays(MAX_DAYS_AHEAD)) || end.isBefore(start)
                || ChronoUnit.DAYS.between(start, end) > MAX_LENGTH_DAYS) {
            throw new ApiProblemException(TripProblem.INVALID_TRIP_DATES);
        }
    }

    private void consumeRead(SessionPrincipal caller) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
    }
}
