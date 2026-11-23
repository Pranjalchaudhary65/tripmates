package com.tripmate.vibe.service;

import com.tripmate.vibe.VibeApi;
import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VibeQuizTest {

    @Test
    void hasFourteenQuestionsCoveringEveryDimension() {
        assertThat(VibeQuiz.questions()).hasSize(14);
        Set<VibeQuiz.Dimension> covered = VibeQuiz.questions().stream().map(VibeQuiz.Question::dimension)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(VibeQuiz.Dimension.class)));
        assertThat(covered).containsExactlyInAnyOrder(VibeQuiz.Dimension.values());
        assertThat(VibeApi.FIRST_OPTIONS.keySet())
                .containsExactlyInAnyOrderElementsOf(VibeQuiz.questions().stream().map(VibeQuiz.Question::id).toList());
    }

    @Test
    void everyCategoricalOptionLandsOnItsEnumAndEveryScaleOptionOnOneToFive() {
        for (VibeQuiz.Question question : VibeQuiz.questions()) {
            int size = switch (question.dimension()) {
                case BUDGET_BAND -> BudgetBand.values().length;
                case PACE -> Pace.values().length;
                case WAKE_TIME -> WakeTime.values().length;
                case PLANNING_STYLE -> PlanningStyle.values().length;
                case EXPENSE_SPLIT_STYLE -> ExpenseSplitStyle.values().length;
                case SOCIAL_ENERGY, RISK_APPETITE, FOOD_ADVENTUROUSNESS -> 5;
            };
            assertThat(question.options()).as(question.id()).hasSize(size);
        }
    }

    @Test
    void firstOptionsScoreTheLowEndOfEveryDimension() {
        VibeDimensions scored = VibeQuiz.score(VibeApi.FIRST_OPTIONS);

        assertThat(scored).isEqualTo(new VibeDimensions(BudgetBand.SHOESTRING, Pace.PACKED, WakeTime.EARLY_BIRD,
                PlanningStyle.DETAILED_ITINERARY, 3, 1, 1, ExpenseSplitStyle.STRICT_SPLIT));
    }

    @Test
    void theReversedQuestionPullsTheOtherWay() {
        VibeDimensions social = VibeQuiz.score(VibeApi.answers(Map.of("social-evenings", "5", "social-alone", "1")));
        VibeDimensions solitary = VibeQuiz.score(VibeApi.answers(Map.of("social-evenings", "1", "social-alone", "5")));

        assertThat(social.socialEnergy()).isEqualTo(5);
        assertThat(solitary.socialEnergy()).isEqualTo(1);
    }

    @Test
    void twoAnswersAverageAndRoundHalfUp() {
        VibeDimensions dimensions = VibeQuiz.score(VibeApi.answers(Map.of(
                "budget-stay", "a", "budget-transport", "b",
                "pace-day", "c", "pace-rest", "c",
                "risk-adventure", "2", "risk-detour", "3",
                "wake-morning", "b", "wake-sunrise", "c")));

        assertThat(dimensions.budgetBand()).isEqualTo(BudgetBand.MID);
        assertThat(dimensions.pace()).isEqualTo(Pace.SLOW);
        assertThat(dimensions.riskAppetite()).isEqualTo(3);
        assertThat(dimensions.wakeTime()).isEqualTo(WakeTime.NIGHT_OWL);
    }

    @Test
    void theTopOptionsReachTheTopOfEveryScale() {
        VibeDimensions dimensions = VibeQuiz.score(VibeApi.answers(Map.ofEntries(
                Map.entry("budget-stay", "d"), Map.entry("budget-transport", "d"),
                Map.entry("plan-before", "c"), Map.entry("plan-change", "c"),
                Map.entry("risk-adventure", "5"), Map.entry("risk-detour", "5"),
                Map.entry("food-street", "5"), Map.entry("expense-split", "alternate"))));

        assertThat(dimensions.budgetBand()).isEqualTo(BudgetBand.LUXURY);
        assertThat(dimensions.planningStyle()).isEqualTo(PlanningStyle.FULLY_SPONTANEOUS);
        assertThat(dimensions.riskAppetite()).isEqualTo(5);
        assertThat(dimensions.foodAdventurousness()).isEqualTo(5);
        assertThat(dimensions.expenseSplitStyle()).isEqualTo(ExpenseSplitStyle.ONE_PAYS_ALTERNATE);
    }

    @Test
    void aMissingOrForeignAnswerCannotBeScored() {
        Map<String, String> missing = new HashMap<>(VibeApi.FIRST_OPTIONS);
        missing.remove("food-street");

        assertThatThrownBy(() -> VibeQuiz.score(missing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VibeQuiz.score(VibeApi.answers(Map.of("food-street", "6"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAQuestionsOwnOptionsAreAnswers() {
        assertThat(VibeQuiz.isAnswer("budget-stay", "d")).isTrue();
        assertThat(VibeQuiz.isAnswer("budget-stay", "e")).isFalse();
        assertThat(VibeQuiz.isAnswer("budget-stay", "1")).isFalse();
        assertThat(VibeQuiz.isAnswer("nope", "a")).isFalse();
    }
}
