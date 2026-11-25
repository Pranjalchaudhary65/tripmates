package com.tripmate.trust.service;

import com.tripmate.identity.entity.VerificationTier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The trust score as a pure function of its inputs (DESIGN.md, Trust Score
 * Engine spec, and its "As built" notes). Four components, each 0-100:
 *
 * <ul>
 *   <li><b>Verification</b> (30%): tier points (EMAIL 10, PHONE 25,
 *       SELFIE_MATCH 50, the tier reached, not a sum) plus 2 per active
 *       voucher up to 10, out of 60.</li>
 *   <li><b>Peer rating</b> (30%): a Bayesian average of visible review stars
 *       against a prior of 3.5 worth 10 reviews, each review weighted by its
 *       age, so one 5-star review cannot outrank a long record.</li>
 *   <li><b>Completion</b> (20%): trips travelled to the end, smoothed so a
 *       new account starts at 50 and moves toward 100 as trips add up.</li>
 *   <li><b>Reliability</b> (20%): 100 minus fixed deductions for no-shows,
 *       late changes of plan, ghosted settlements and strongly flagged chat
 *       messages, each fading with age. Ghosting fades to half, never to
 *       nothing.</li>
 * </ul>
 * Then a flat, capped deduction for vouching for someone who was suspended.
 *
 * <p>Only what {@link Inputs} can express can move a score. There is no way
 * to pass a comfort rating, a safety cancellation, an SOS or any other safety
 * action, and no way to pass how often someone replies: not answering
 * someone you would rather avoid must cost nothing.
 *
 * <p>Every displayed number is rounded before it is added, so the points
 * shown for each component add up to the score exactly.
 */
public final class TrustScoreFormula {

    public static final String VERSION = "v1";

    static final BigDecimal VERIFICATION_WEIGHT = new BigDecimal("0.30");

    static final BigDecimal PEER_WEIGHT = new BigDecimal("0.30");

    static final BigDecimal COMPLETION_WEIGHT = new BigDecimal("0.20");

    static final BigDecimal RELIABILITY_WEIGHT = new BigDecimal("0.20");

    static final Map<VerificationTier, Integer> TIER_POINTS = Map.of(
            VerificationTier.NONE, 0, VerificationTier.EMAIL, 10,
            VerificationTier.PHONE, 25, VerificationTier.SELFIE_MATCH, 50);

    static final int POINTS_PER_VOUCHER = 2;

    static final int VOUCH_BONUS_CAP = 10;

    static final int VERIFICATION_MAX = 60;

    static final double PRIOR_REVIEWS = 10;

    static final double PRIOR_STARS = 3.5;

    /** A signal a year old counts for about 15% of one from today. */
    static final double DECAY_PER_DAY = -Math.log(0.15) / 365;

    /** Ghosting someone fades to half its weight and stays there. */
    static final double GHOST_FLOOR = 0.5;

    static final double VOUCH_PENALTY_EACH = 3;

    static final double VOUCH_PENALTY_CAP = 9;

    private TrustScoreFormula() {
    }

    public static Breakdown compute(Inputs inputs, Instant now) {
        Verification verification = verification(inputs.tier(), inputs.activeVouchers());
        PeerRating peer = peerRating(inputs.ratings(), now);
        Completion completion = completion(inputs.finishedTrips());
        Reliability reliability = reliability(inputs.events(), now);
        VouchPenalty vouchPenalty = vouchPenalty(inputs.events(), now);

        BigDecimal total = verification.points().add(peer.points()).add(completion.points())
                .add(reliability.points()).subtract(vouchPenalty.points());
        BigDecimal score = total.max(BigDecimal.ZERO).min(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        return new Breakdown(score, VERSION, verification, peer, completion, reliability, vouchPenalty);
    }

    static Verification verification(VerificationTier tier, long activeVouchers) {
        int tierPoints = TIER_POINTS.get(tier);
        int bonus = (int) Math.min(VOUCH_BONUS_CAP, POINTS_PER_VOUCHER * Math.max(0, activeVouchers));
        BigDecimal value = oneDecimal(100.0 * (tierPoints + bonus) / VERIFICATION_MAX);
        return new Verification(tier, tierPoints, activeVouchers, bonus, value, VERIFICATION_WEIGHT,
                points(value, VERIFICATION_WEIGHT));
    }

    static PeerRating peerRating(List<Rating> ratings, Instant now) {
        double weightSum = 0;
        double weightedStars = 0;
        int starSum = 0;
        for (Rating rating : ratings) {
            double weight = decay(rating.visibleAt(), now);
            weightSum += weight;
            weightedStars += weight * rating.stars();
            starSum += rating.stars();
        }
        double bayesian = (PRIOR_REVIEWS * PRIOR_STARS + weightedStars) / (PRIOR_REVIEWS + weightSum);
        BigDecimal value = oneDecimal(100.0 * (bayesian - 1) / 4);
        BigDecimal average = ratings.isEmpty() ? null
                : BigDecimal.valueOf(starSum).divide(BigDecimal.valueOf(ratings.size()), 2, RoundingMode.HALF_UP);
        return new PeerRating(ratings.size(), average, BigDecimal.valueOf(bayesian).setScale(2, RoundingMode.HALF_UP),
                value, PEER_WEIGHT, points(value, PEER_WEIGHT));
    }

    static Completion completion(int finishedTrips) {
        int trips = Math.max(0, finishedTrips);
        BigDecimal value = oneDecimal(100.0 * (trips + 1) / (trips + 2));
        return new Completion(trips, value, COMPLETION_WEIGHT, points(value, COMPLETION_WEIGHT));
    }

    static Reliability reliability(List<Input> events, Instant now) {
        List<Deduction> deductions = new ArrayList<>();
        BigDecimal deducted = BigDecimal.ZERO;
        for (Input event : events) {
            Penalty penalty = penaltyFor(event);
            if (penalty == null) {
                continue;
            }
            double weight = decay(event.occurredAt(), now);
            if (penalty.ghosting()) {
                weight = Math.max(GHOST_FLOOR, weight);
            }
            BigDecimal current = oneDecimal(penalty.points() * weight);
            deducted = deducted.add(current);
            deductions.add(new Deduction(event.type(), event.detail(), event.occurredAt(),
                    oneDecimal(penalty.points()), current));
        }
        deductions.sort(Comparator.comparing(Deduction::occurredAt).reversed());
        BigDecimal value = new BigDecimal("100").subtract(deducted).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
        return new Reliability(List.copyOf(deductions), value, RELIABILITY_WEIGHT, points(value, RELIABILITY_WEIGHT));
    }

    static VouchPenalty vouchPenalty(List<Input> events, Instant now) {
        int count = 0;
        double total = 0;
        for (Input event : events) {
            if (event.type() == InputType.VOUCH_PENALIZED) {
                count++;
                total += VOUCH_PENALTY_EACH * decay(event.occurredAt(), now);
            }
        }
        return new VouchPenalty(count, BigDecimal.valueOf(Math.min(VOUCH_PENALTY_CAP, total))
                .setScale(2, RoundingMode.HALF_UP));
    }

    /** Null for an input that is not a reliability deduction. */
    static Penalty penaltyFor(Input event) {
        return switch (event.type()) {
            case MEETUP_NO_SHOW -> new Penalty(20, true);
            case MEETUP_LATE_CANCEL -> new Penalty(8, false);
            case SETTLEMENT_OVERDUE -> "PAYER".equals(event.detail()) ? new Penalty(15, true) : new Penalty(8, true);
            case CHAT_MESSAGE_FLAGGED -> new Penalty(10, false);
            case VOUCH_PENALIZED -> null;
        };
    }

    static double decay(Instant at, Instant now) {
        double days = Math.max(0, Duration.between(at, now).toSeconds()) / 86_400.0;
        return Math.exp(-DECAY_PER_DAY * days);
    }

    private static BigDecimal oneDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal points(BigDecimal value, BigDecimal weight) {
        return value.multiply(weight).setScale(2, RoundingMode.HALF_UP);
    }

    record Penalty(double points, boolean ghosting) {
    }

    /** The inputs that carry their own weight in the score; tier and vouches are read live instead. */
    public enum InputType {
        MEETUP_NO_SHOW, MEETUP_LATE_CANCEL, SETTLEMENT_OVERDUE, CHAT_MESSAGE_FLAGGED, VOUCH_PENALIZED
    }

    public record Rating(int stars, Instant visibleAt) {

        public Rating {
            if (stars < 1 || stars > 5) {
                throw new IllegalArgumentException("stars must be 1 to 5");
            }
            Objects.requireNonNull(visibleAt, "visibleAt");
        }
    }

    /** @param detail the settlement role, or the chat flag type; null otherwise */
    public record Input(InputType type, String detail, Instant occurredAt) {

        public Input {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    public record Inputs(VerificationTier tier, long activeVouchers, List<Rating> ratings, int finishedTrips,
                         List<Input> events) {

        public Inputs {
            Objects.requireNonNull(tier, "tier");
            ratings = List.copyOf(ratings);
            events = List.copyOf(events);
        }
    }

    /** The whole score and how it was reached; stored as a snapshot's components and shown to its owner. */
    public record Breakdown(BigDecimal score, String formulaVersion, Verification verification, PeerRating peerRating,
                            Completion completion, Reliability reliability, VouchPenalty vouchPenalty) {
    }

    public record Verification(VerificationTier tier, int tierPoints, long activeVouchers, int vouchBonus,
                               BigDecimal value, BigDecimal weight, BigDecimal points) {
    }

    /**
     * @param averageStars  the plain average of visible reviews; null with none
     * @param weightedStars the Bayesian, age-weighted average the value comes from
     */
    public record PeerRating(int visibleReviews, BigDecimal averageStars, BigDecimal weightedStars, BigDecimal value,
                             BigDecimal weight, BigDecimal points) {
    }

    public record Completion(int finishedTrips, BigDecimal value, BigDecimal weight, BigDecimal points) {
    }

    public record Reliability(List<Deduction> deductions, BigDecimal value, BigDecimal weight, BigDecimal points) {
    }

    /**
     * @param fullPoints what it took off when it happened
     * @param nowPoints  what it takes off today, after fading
     */
    public record Deduction(InputType type, String detail, Instant occurredAt, BigDecimal fullPoints,
                            BigDecimal nowPoints) {
    }

    /** @param points taken off after the four components are added */
    public record VouchPenalty(int count, BigDecimal points) {
    }
}
