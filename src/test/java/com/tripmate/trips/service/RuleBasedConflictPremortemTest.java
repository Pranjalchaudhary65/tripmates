package com.tripmate.trips.service;

import com.tripmate.trips.service.ConflictPremortem.Topic;
import com.tripmate.trips.service.MatchScorer.Dimension;
import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedConflictPremortemTest {

    private final RuleBasedConflictPremortem premortem = new RuleBasedConflictPremortem();

    private static VibeDimensions vibe(BudgetBand budget, Pace pace, WakeTime wake, PlanningStyle planning, int social,
                                       int risk, int food, ExpenseSplitStyle expenses) {
        return new VibeDimensions(budget, pace, wake, planning, social, risk, food, expenses);
    }

    @Test
    void twoTravellersAlikeHaveNothingToTalkThrough() {
        VibeDimensions same = vibe(BudgetBand.MID, Pace.BALANCED, WakeTime.AVERAGE, PlanningStyle.ROUGH_PLAN, 3, 3, 3,
                ExpenseSplitStyle.WHOEVER_CAN);

        assertThat(premortem.topics(same, same, "Asha")).isEmpty();
    }

    @Test
    void oppositeEndsAndWideGapsAreFlaggedWithAQuestionEach() {
        VibeDimensions mine = vibe(BudgetBand.SHOESTRING, Pace.PACKED, WakeTime.EARLY_BIRD,
                PlanningStyle.DETAILED_ITINERARY, 1, 5, 1, ExpenseSplitStyle.STRICT_SPLIT);
        VibeDimensions theirs = vibe(BudgetBand.COMFORT, Pace.SLOW, WakeTime.NIGHT_OWL,
                PlanningStyle.FULLY_SPONTANEOUS, 5, 1, 4, ExpenseSplitStyle.WHOEVER_CAN);

        List<Topic> topics = premortem.topics(mine, theirs, "Asha");

        assertThat(topics).extracting(Topic::dimension).containsExactly(Dimension.BUDGET, Dimension.PACE,
                Dimension.WAKE_TIME, Dimension.PLANNING, Dimension.SOCIAL_ENERGY, Dimension.RISK_APPETITE,
                Dimension.FOOD, Dimension.EXPENSES);
        assertThat(topics.getFirst().gap()).isEqualTo("You plan for a shoestring budget; Asha plans for comfort.");
        assertThat(topics).allSatisfy(topic -> assertThat(topic.question()).endsWith("."));
    }

    @Test
    void middlingDifferencesAreLeftOut() {
        VibeDimensions mine = vibe(BudgetBand.MID, Pace.PACKED, WakeTime.EARLY_BIRD, PlanningStyle.ROUGH_PLAN, 2, 2, 2,
                ExpenseSplitStyle.WHOEVER_CAN);
        VibeDimensions theirs = vibe(BudgetBand.COMFORT, Pace.BALANCED, WakeTime.AVERAGE,
                PlanningStyle.FULLY_SPONTANEOUS, 4, 4, 4, ExpenseSplitStyle.ONE_PAYS_ALTERNATE);

        assertThat(premortem.topics(mine, theirs, "Asha")).isEmpty();
    }
}
