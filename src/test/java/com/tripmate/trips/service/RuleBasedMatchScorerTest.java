package com.tripmate.trips.service;

import com.tripmate.trips.service.MatchScorer.Dimension;
import com.tripmate.trips.service.MatchScorer.MatchResult;
import com.tripmate.trips.service.MatchScorer.Reason;
import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;
import org.junit.jupiter.api.Test;

import java.util.List;


import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedMatchScorerTest {

    private final RuleBasedMatchScorer scorer = new RuleBasedMatchScorer();

    private static final VibeDimensions EARLY_PLANNER = new VibeDimensions(BudgetBand.MID, Pace.PACKED,
            WakeTime.EARLY_BIRD, PlanningStyle.DETAILED_ITINERARY, 2, 2, 3, ExpenseSplitStyle.STRICT_SPLIT);

    private static final VibeDimensions NIGHT_DRIFTER = new VibeDimensions(BudgetBand.LUXURY, Pace.SLOW,
            WakeTime.NIGHT_OWL, PlanningStyle.FULLY_SPONTANEOUS, 5, 5, 3, ExpenseSplitStyle.WHOEVER_CAN);

    @Test
    void theWeightsSumToOneHundredAndCoverEveryDimension() {
        assertThat(RuleBasedMatchScorer.WEIGHTS).containsOnlyKeys(Dimension.values());
        assertThat(RuleBasedMatchScorer.WEIGHTS.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void twoIdenticalProfilesOnATripAtTheirOwnBudgetScoreOneHundredWithEveryDimensionAMatch() {
        MatchResult result = scorer.score(EARLY_PLANNER, EARLY_PLANNER, BudgetBand.MID);

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.matches()).extracting(Reason::dimension).containsExactlyInAnyOrder(Dimension.values());
        assertThat(labels(result.matches())).contains("You are both early birds.",
                "The trip's budget is mid-range, the same as yours.", "You both like a detailed itinerary.",
                "You have the same social energy.", "You both prefer splitting every bill exactly.");
    }

    @Test
    void oppositeProfilesScoreLowAndSayWhatDiffers() {
        MatchResult result = scorer.score(EARLY_PLANNER, NIGHT_DRIFTER, BudgetBand.LUXURY);

        // Budget 20*(1/3), pace 0, wake 0, planning 0, social 15*(1/4), risk 10*(1/4), food 5, expenses 0:
        // 17.92, rounded half up.
        assertThat(result.score()).isEqualTo(18);
        assertThat(result.matches()).extracting(Reason::dimension).containsExactly(Dimension.FOOD);
        assertThat(result.mismatches()).extracting(Reason::dimension).containsExactly(Dimension.BUDGET,
                Dimension.PACE, Dimension.SOCIAL_ENERGY, Dimension.EXPENSES, Dimension.WAKE_TIME,
                Dimension.PLANNING, Dimension.RISK_APPETITE);
        assertThat(labels(result.mismatches())).contains("You are an early bird; the host is a night owl.",
                "The trip's budget is luxury; yours is mid-range.", "The host is much more social than you.",
                "You prefer splitting every bill exactly; the host prefers whoever can paying.");
    }

    @Test
    void theTripsBudgetCountsRatherThanTheHostsUsualOne() {
        MatchResult atHostsBudget = scorer.score(EARLY_PLANNER, NIGHT_DRIFTER, BudgetBand.LUXURY);
        MatchResult atViewersBudget = scorer.score(EARLY_PLANNER, NIGHT_DRIFTER, BudgetBand.MID);

        assertThat(atViewersBudget.score() - atHostsBudget.score()).isEqualTo(13);
        assertThat(labels(atViewersBudget.matches())).contains("The trip's budget is mid-range, the same as yours.");
    }

    @Test
    void middlingDimensionsAreLeftOutOfTheExplanationRatherThanCalledEither() {
        VibeDimensions balanced = new VibeDimensions(BudgetBand.MID, Pace.BALANCED, WakeTime.AVERAGE,
                PlanningStyle.ROUGH_PLAN, 4, 4, 5, ExpenseSplitStyle.ONE_PAYS_ALTERNATE);

        MatchResult result = scorer.score(EARLY_PLANNER, balanced, BudgetBand.MID);

        // Pace, wake, planning one step apart (1/2); social and risk two apart (1/2); food two apart (1/2);
        // expenses strict against take-turns (0).
        assertThat(result.matches()).extracting(Reason::dimension).containsExactly(Dimension.BUDGET);
        assertThat(result.mismatches()).extracting(Reason::dimension).containsExactly(Dimension.EXPENSES);
        assertThat(result.score()).isEqualTo(53);
    }

    @Test
    void closeButNotEqualValuesAreStillMatches() {
        VibeDimensions near = new VibeDimensions(BudgetBand.COMFORT, Pace.PACKED, WakeTime.EARLY_BIRD,
                PlanningStyle.DETAILED_ITINERARY, 3, 2, 3, ExpenseSplitStyle.STRICT_SPLIT);

        MatchResult result = scorer.score(EARLY_PLANNER, near, BudgetBand.COMFORT);

        assertThat(labels(result.matches())).contains("The trip's budget is comfort, close to your mid-range.",
                "You have a similar social energy.");
        assertThat(result.mismatches()).isEmpty();
    }

    @Test
    void theSameTwoProfilesAlwaysGetTheSameResult() {
        assertThat(scorer.score(EARLY_PLANNER, NIGHT_DRIFTER, BudgetBand.COMFORT))
                .isEqualTo(scorer.score(EARLY_PLANNER, NIGHT_DRIFTER, BudgetBand.COMFORT));
    }

    @Test
    void everyResultStaysWithinZeroToOneHundred() {
        for (BudgetBand budget : BudgetBand.values()) {
            for (Pace pace : Pace.values()) {
                for (ExpenseSplitStyle expenses : ExpenseSplitStyle.values()) {
                    VibeDimensions other = new VibeDimensions(budget, pace, WakeTime.AVERAGE,
                            PlanningStyle.ROUGH_PLAN, 1, 5, 1, expenses);
                    assertThat(scorer.score(EARLY_PLANNER, other, budget).score()).isBetween(0, 100);
                }
            }
        }
    }

    private static String[] labels(List<Reason> reasons) {
        return reasons.stream().map(Reason::label).toArray(String[]::new);
    }
}
