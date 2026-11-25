package com.tripmate.trust.service;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.trust.service.TrustScoreFormula.Breakdown;
import com.tripmate.trust.service.TrustScoreFormula.Input;
import com.tripmate.trust.service.TrustScoreFormula.InputType;
import com.tripmate.trust.service.TrustScoreFormula.Inputs;
import com.tripmate.trust.service.TrustScoreFormula.Rating;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TrustScoreFormulaTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void aNewEmailVerifiedAccountStartsFromThePriorsNotFromZero() {
        Breakdown score = compute(VerificationTier.EMAIL, 0, List.of(), 0, List.of());

        assertThat(score.verification().value()).isEqualByComparingTo("16.7");
        assertThat(score.peerRating().value()).as("3.5-star prior").isEqualByComparingTo("62.5");
        assertThat(score.peerRating().averageStars()).isNull();
        assertThat(score.completion().value()).isEqualByComparingTo("50.0");
        assertThat(score.reliability().value()).isEqualByComparingTo("100.0");
        assertThat(score.score()).isEqualByComparingTo("53.76");
    }

    @Test
    void tierIsTheTierReachedNotASumAndVouchesAddTwoEachUpToTen() {
        assertThat(compute(VerificationTier.PHONE, 0, List.of(), 0, List.of()).verification().tierPoints())
                .isEqualTo(25);
        Breakdown selfie = compute(VerificationTier.SELFIE_MATCH, 0, List.of(), 0, List.of());
        assertThat(selfie.verification().tierPoints()).isEqualTo(50);
        assertThat(selfie.verification().value()).isEqualByComparingTo("83.3");

        Breakdown fiveVouches = compute(VerificationTier.SELFIE_MATCH, 5, List.of(), 0, List.of());
        assertThat(fiveVouches.verification().vouchBonus()).isEqualTo(10);
        assertThat(fiveVouches.verification().value()).isEqualByComparingTo("100.0");
        assertThat(compute(VerificationTier.SELFIE_MATCH, 9, List.of(), 0, List.of()).verification().vouchBonus())
                .as("capped at +10").isEqualTo(10);
        assertThat(compute(VerificationTier.NONE, 0, List.of(), 0, List.of()).verification().value())
                .isEqualByComparingTo("0.0");
    }

    @Test
    void oneFiveStarReviewCannotOutrankALongGoodRecord() {
        Breakdown one = compute(VerificationTier.EMAIL, 0, List.of(new Rating(5, NOW)), 0, List.of());
        List<Rating> forty = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            forty.add(new Rating(i % 5 == 0 ? 3 : (i % 5 == 1 ? 4 : 5), NOW.minus(Duration.ofDays(i))));
        }
        Breakdown longRecord = compute(VerificationTier.EMAIL, 0, forty, 0, List.of());

        assertThat(one.peerRating().weightedStars()).isEqualByComparingTo("3.64");
        assertThat(one.peerRating().averageStars()).isEqualByComparingTo("5.00");
        assertThat(longRecord.peerRating().value()).isGreaterThan(one.peerRating().value());
    }

    @Test
    void aReviewFromAYearAgoCountsForAboutFifteenPercent() {
        assertThat(TrustScoreFormula.decay(NOW.minus(Duration.ofDays(365)), NOW)).isCloseTo(0.15,
                org.assertj.core.data.Offset.offset(0.001));
        Breakdown old = compute(VerificationTier.EMAIL, 0, List.of(new Rating(5, NOW.minus(Duration.ofDays(365)))),
                0, List.of());
        assertThat(old.peerRating().weightedStars()).isEqualByComparingTo("3.52");
    }

    @Test
    void finishedTripsMoveCompletionFromFiftyTowardAHundred() {
        assertThat(TrustScoreFormula.completion(0).value()).isEqualByComparingTo("50.0");
        assertThat(TrustScoreFormula.completion(1).value()).isEqualByComparingTo("66.7");
        assertThat(TrustScoreFormula.completion(8).value()).isEqualByComparingTo("90.0");
    }

    @Test
    void ghostingFadesToHalfAndStaysWhileOtherDeductionsFadeAway() {
        Instant twoYearsAgo = NOW.minus(Duration.ofDays(730));
        Breakdown noShowToday = compute(VerificationTier.EMAIL, 0, List.of(), 0,
                List.of(new Input(InputType.MEETUP_NO_SHOW, null, NOW)));
        Breakdown oldNoShow = compute(VerificationTier.EMAIL, 0, List.of(), 0,
                List.of(new Input(InputType.MEETUP_NO_SHOW, null, twoYearsAgo)));
        Breakdown oldFlag = compute(VerificationTier.EMAIL, 0, List.of(), 0,
                List.of(new Input(InputType.CHAT_MESSAGE_FLAGGED, "MONEY_REQUEST", twoYearsAgo)));

        assertThat(noShowToday.reliability().value()).isEqualByComparingTo("80.0");
        assertThat(oldNoShow.reliability().value()).as("floor at half of 20").isEqualByComparingTo("90.0");
        assertThat(oldFlag.reliability().value()).isEqualByComparingTo("99.8");
        assertThat(oldNoShow.reliability().deductions()).singleElement().satisfies(deduction -> {
            assertThat(deduction.fullPoints()).isEqualByComparingTo("20.0");
            assertThat(deduction.nowPoints()).isEqualByComparingTo("10.0");
        });
    }

    @Test
    void ghostingASettlementCostsThePayerMoreThanThePayee() {
        Breakdown payer = compute(VerificationTier.EMAIL, 0, List.of(), 0,
                List.of(new Input(InputType.SETTLEMENT_OVERDUE, "PAYER", NOW)));
        Breakdown payee = compute(VerificationTier.EMAIL, 0, List.of(), 0,
                List.of(new Input(InputType.SETTLEMENT_OVERDUE, "PAYEE", NOW)));

        assertThat(payer.reliability().value()).isEqualByComparingTo("85.0");
        assertThat(payee.reliability().value()).isEqualByComparingTo("92.0");
    }

    @Test
    void theVouchPenaltyIsFlatCappedAndTakenOffAfterTheComponents() {
        List<Input> four = Collections.nCopies(4, new Input(InputType.VOUCH_PENALIZED, null, NOW));
        Breakdown penalised = compute(VerificationTier.SELFIE_MATCH, 0, List.of(), 0, four);
        Breakdown clean = compute(VerificationTier.SELFIE_MATCH, 0, List.of(), 0, List.of());

        assertThat(penalised.vouchPenalty().count()).isEqualTo(4);
        assertThat(penalised.vouchPenalty().points()).isEqualByComparingTo("9.00");
        assertThat(penalised.reliability().value()).as("not a reliability deduction").isEqualByComparingTo("100.0");
        assertThat(clean.score().subtract(penalised.score())).isEqualByComparingTo("9.00");
    }

    @Test
    void theScoreNeverLeavesZeroToAHundred() {
        List<Input> awful = new ArrayList<>(Collections.nCopies(10, new Input(InputType.MEETUP_NO_SHOW, null, NOW)));
        awful.addAll(Collections.nCopies(5, new Input(InputType.VOUCH_PENALIZED, null, NOW)));
        Breakdown low = compute(VerificationTier.NONE, 0, Collections.nCopies(50, new Rating(1, NOW)), 0, awful);
        Breakdown high = compute(VerificationTier.SELFIE_MATCH, 5, Collections.nCopies(200, new Rating(5, NOW)), 500,
                List.of());

        assertThat(low.reliability().value()).isEqualByComparingTo("0.0");
        assertThat(low.score()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(high.score()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
    }

    @Test
    void theShownPointsAlwaysAddUpToTheScore() {
        Random random = new Random(11);
        for (int run = 0; run < 2_000; run++) {
            List<Rating> ratings = new ArrayList<>();
            for (int i = random.nextInt(15); i > 0; i--) {
                ratings.add(new Rating(1 + random.nextInt(5), NOW.minus(Duration.ofDays(random.nextInt(900)))));
            }
            List<Input> events = new ArrayList<>();
            for (int i = random.nextInt(6); i > 0; i--) {
                InputType type = InputType.values()[random.nextInt(InputType.values().length)];
                events.add(new Input(type, type == InputType.SETTLEMENT_OVERDUE ? "PAYEE" : null,
                        NOW.minus(Duration.ofDays(random.nextInt(900)))));
            }
            Breakdown b = compute(VerificationTier.values()[random.nextInt(4)], random.nextInt(8), ratings,
                    random.nextInt(12), events);
            BigDecimal sum = b.verification().points().add(b.peerRating().points()).add(b.completion().points())
                    .add(b.reliability().points()).subtract(b.vouchPenalty().points())
                    .max(BigDecimal.ZERO).min(new BigDecimal("100"));
            assertThat(b.score()).as("run %d", run).isEqualByComparingTo(sum);
        }
    }

    private static Breakdown compute(VerificationTier tier, long vouchers, List<Rating> ratings, int trips,
                                     List<Input> events) {
        return TrustScoreFormula.compute(new Inputs(tier, vouchers, ratings, trips, events), NOW);
    }
}
