package com.tripmate.trust.service;

import com.tripmate.identity.service.AccountService;
import com.tripmate.identity.service.AgePolicy;
import com.tripmate.identity.service.TrustScoreAccounts;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.platform.error.CommonProblem;
import com.tripmate.platform.error.Constraints;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.trips.service.TripCalendar;
import com.tripmate.trips.service.TripCrew;
import com.tripmate.trips.service.TripCrew.Crew;
import com.tripmate.trust.repository.ReviewQueries;
import com.tripmate.trust.repository.ReviewQueries.ReceivedReview;
import com.tripmate.trust.repository.ReviewQueries.ReviewRow;
import com.tripmate.trust.repository.TrustQueries;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Double-blind reviews between people who travelled together (DESIGN.md §8).
 *
 * <p>Who: the host and every companion still on the trip when it ended, each
 * reviewing each other. A companion who stepped back for their safety
 * neither reviews nor can be reviewed on that trip, so stepping back can
 * never earn a retaliatory rating. Anyone else asking about the trip gets
 * the 404 an unknown trip gets.
 *
 * <p>When: from the day after the trip's last day until {@code review-window}
 * later (IST). One review per person per co-traveller per trip, never
 * edited.
 *
 * <p>Double-blind: a review stays hidden from everyone, including the
 * trust score, until the other person of the pair has reviewed back, when
 * both become visible in the transaction that wrote the second; or until the
 * window closes, when whatever was written becomes visible. The two writers
 * of a pair are serialised by an advisory lock, so two reviews submitted at
 * the same moment cannot each miss the other.
 */
@Service
public class ReviewService {

    static final String READ_BUCKET = "trust-read";

    static final String WRITE_BUCKET = "review-write";

    static final int RECEIVED_LIMIT = 50;

    private final ReviewQueries reviews;

    private final TrustQueries trust;

    private final TripCrew crews;

    private final TrustScoreAccounts accounts;

    private final AccountService accountService;

    private final RateLimiter rateLimiter;

    private final TrustProperties properties;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Clock clock;

    public ReviewService(ReviewQueries reviews, TrustQueries trust, TripCrew crews, TrustScoreAccounts accounts,
                         AccountService accountService, RateLimiter rateLimiter, TrustProperties properties,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.reviews = reviews;
        this.trust = trust;
        this.crews = crews;
        this.accounts = accounts;
        this.accountService = accountService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    /** The review screen of one trip for one traveller on it. */
    public TripReviews forTrip(SessionPrincipal caller, UUID tripId) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
        return Objects.requireNonNull(readOnly.execute(status -> {
            accountService.requireActive(caller);
            return view(caller.userId(), travellerCrew(caller.userId(), tripId));
        }));
    }

    public TripReviews submit(SessionPrincipal caller, UUID tripId, UUID revieweeId, int rating, String rawComment) {
        rateLimiter.consume(WRITE_BUCKET, caller.userId().toString(), properties.writeLimit());
        if (rating < 1 || rating > 5) {
            throw new ApiProblemException(ReviewProblem.INVALID_RATING);
        }
        String comment;
        try {
            comment = ReviewText.clean(rawComment).orElse(null);
        } catch (IllegalArgumentException tooLong) {
            throw new ApiProblemException(ReviewProblem.INVALID_COMMENT);
        }
        UUID me = caller.userId();
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                accountService.lockActive(caller);
                Crew crew = travellerCrew(me, tripId);
                if (me.equals(revieweeId) || !crew.isTraveller(revieweeId)) {
                    throw new ApiProblemException(ReviewProblem.NOT_A_TRAVEL_COMPANION);
                }
                switch (window(crew).status()) {
                    case NOT_REVIEWABLE -> throw new ApiProblemException(ReviewProblem.TRIP_NOT_REVIEWABLE);
                    case NOT_YET -> throw new ApiProblemException(ReviewProblem.REVIEW_WINDOW_NOT_OPEN);
                    case CLOSED -> throw new ApiProblemException(ReviewProblem.REVIEW_WINDOW_CLOSED);
                    case OPEN -> {
                    }
                }
                reviews.lockPair(tripId, me, revieweeId);
                List<ReviewRow> pair = reviews.byTrip(tripId).stream()
                        .filter(row -> isBetween(row, me, revieweeId)).toList();
                if (pair.stream().anyMatch(row -> row.reviewerId().equals(me))) {
                    throw new ApiProblemException(ReviewProblem.ALREADY_REVIEWED);
                }
                Instant now = clock.instant();
                reviews.insert(tripId, me, revieweeId, rating, comment, now);
                if (pair.stream().anyMatch(row -> row.reviewerId().equals(revieweeId))) {
                    reviews.revealPair(tripId, me, revieweeId, now);
                    trust.markDirty(List.of(me, revieweeId), now);
                }
                return view(me, crew);
            }));
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uq_reviews_pair")) {
                throw new ApiProblemException(ReviewProblem.ALREADY_REVIEWED);
            }
            throw e;
        }
    }

    /** The trips waiting for the caller's reviews and the reviews about them that are visible. */
    public MyReviews mine(SessionPrincipal caller) {
        rateLimiter.consume(READ_BUCKET, caller.userId().toString(), properties.readLimit());
        UUID me = caller.userId();
        return Objects.requireNonNull(readOnly.execute(status -> {
            accountService.requireActive(caller);
            List<TripToReview> toReview = new ArrayList<>();
            for (UUID tripId : reviews.tripsInReviewWindow(me, today(), windowDays())) {
                crews.of(tripId).filter(crew -> crew.isTraveller(me)).ifPresent(crew -> {
                    TripReviews view = view(me, crew);
                    long waiting = view.people().stream().filter(p -> p.status() == PersonStatus.TO_WRITE).count();
                    if (view.window().status() == WindowStatus.OPEN && waiting > 0) {
                        toReview.add(new TripToReview(view.trip(), view.window().closesAt(), (int) waiting));
                    }
                });
            }
            return new MyReviews(List.copyOf(toReview), reviews.receivedVisible(me, RECEIVED_LIMIT));
        }));
    }

    /**
     * Reveals what was written on trips whose window has closed, and queues
     * the scores of the people it is about.
     *
     * @return how many reviews became visible
     */
    public int revealClosedWindows() {
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant now = clock.instant();
            List<UUID> reviewees = reviews.revealClosedWindows(now, windowDays());
            trust.markDirty(reviewees, now);
            return reviewees.size();
        }));
    }

    private Crew travellerCrew(UUID me, UUID tripId) {
        return crews.of(tripId).filter(crew -> crew.isTraveller(me))
                .orElseThrow(() -> new ApiProblemException(CommonProblem.NOT_FOUND));
    }

    private TripReviews view(UUID me, Crew crew) {
        Window window = window(crew);
        List<ReviewRow> onTrip = reviews.byTrip(crew.tripId());
        List<Person> people = new ArrayList<>();
        for (UUID other : crew.travellers()) {
            if (other.equals(me)) {
                continue;
            }
            Optional<ReviewRow> mine = onTrip.stream()
                    .filter(row -> row.reviewerId().equals(me) && row.revieweeId().equals(other)).findFirst();
            Optional<ReviewRow> theirs = onTrip.stream()
                    .filter(row -> row.reviewerId().equals(other) && row.revieweeId().equals(me))
                    .filter(ReviewRow::isVisible).findFirst();
            PersonStatus personStatus = mine.map(row -> row.isVisible() ? PersonStatus.REVEALED
                    : PersonStatus.WAITING_FOR_THEM).orElse(window.status() == WindowStatus.OPEN
                    ? PersonStatus.TO_WRITE : PersonStatus.NOT_WRITTEN);
            people.add(new Person(other, accounts.firstName(other).orElse(""), personStatus,
                    mine.map(row -> new WrittenReview(row.rating(), row.comment(), row.submittedAt())).orElse(null),
                    theirs.map(row -> new WrittenReview(row.rating(), row.comment(), row.visibleAt())).orElse(null)));
        }
        TripRef trip = new TripRef(crew.tripId(), crew.title(), crew.destinationName(), crew.startDate(),
                crew.endDate());
        return new TripReviews(trip, window, List.copyOf(people));
    }

    private Window window(Crew crew) {
        LocalDate opensOn = crew.endDate().plusDays(1);
        Instant closesAt = opensOn.atStartOfDay(AgePolicy.LEGAL_ZONE).toInstant().plus(properties.reviewWindow());
        WindowStatus status;
        if (crew.cancelled() || !crew.isLocked()) {
            status = WindowStatus.NOT_REVIEWABLE;
        } else if (today().isBefore(opensOn)) {
            status = WindowStatus.NOT_YET;
        } else if (!clock.instant().isBefore(closesAt)) {
            status = WindowStatus.CLOSED;
        } else {
            status = WindowStatus.OPEN;
        }
        return new Window(status, opensOn, closesAt);
    }

    private static boolean isBetween(ReviewRow row, UUID one, UUID other) {
        return (row.reviewerId().equals(one) && row.revieweeId().equals(other))
                || (row.reviewerId().equals(other) && row.revieweeId().equals(one));
    }

    private LocalDate today() {
        return TripCalendar.today(clock);
    }

    private int windowDays() {
        return (int) properties.reviewWindow().toDays();
    }

    public enum WindowStatus {
        /** Cancelled, or nobody was ever locked in with the host. */
        NOT_REVIEWABLE,
        /** The trip has not ended yet. */
        NOT_YET,
        OPEN,
        CLOSED
    }

    public enum PersonStatus {
        /** The window is open and the caller has not reviewed this person. */
        TO_WRITE,
        /** The caller has; it stays hidden until this person reviews back or the window closes. */
        WAITING_FOR_THEM,
        /** The caller's review is visible, and theirs too if they wrote one. */
        REVEALED,
        /** The window is not open and the caller wrote nothing. */
        NOT_WRITTEN
    }

    public record TripRef(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate) {
    }

    public record Window(WindowStatus status, LocalDate opensOn, Instant closesAt) {
    }

    /**
     * @param at when it was submitted (the caller's own) or became visible (theirs)
     */
    public record WrittenReview(int rating, String comment, Instant at) {
    }

    /**
     * @param mine   the caller's review of this person, whether or not visible yet
     * @param theirs this person's review of the caller, only once visible
     */
    public record Person(UUID userId, String firstName, PersonStatus status, WrittenReview mine,
                         WrittenReview theirs) {
    }

    public record TripReviews(TripRef trip, Window window, List<Person> people) {
    }

    public record TripToReview(TripRef trip, Instant closesAt, int waiting) {
    }

    public record MyReviews(List<TripToReview> toReview, List<ReceivedReview> received) {
    }
}
