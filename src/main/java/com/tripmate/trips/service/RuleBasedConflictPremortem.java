package com.tripmate.trips.service;

import com.tripmate.trips.service.MatchScorer.Dimension;
import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The default pre-mortem: fixed rules for gaps big enough to matter.
 * Ordered dimensions (budget, pace, wake time, planning) are flagged at
 * opposite ends, or two budget bands apart; the 1-5 scales at three or more
 * apart; expenses when one splits every bill exactly and the other does not.
 * Middling differences are left out: the point is the two or three
 * conversations worth having, not a list of every way two people differ.
 */
@Component
@ConditionalOnProperty(prefix = "tripmate.trips", name = "premortem", havingValue = RuleBasedConflictPremortem.NAME,
        matchIfMissing = true)
public class RuleBasedConflictPremortem implements ConflictPremortem {

    public static final String NAME = "rule-based";

    static final int SCALE_GAP = 3;

    private static final Map<BudgetBand, String> BUDGET = Map.of(BudgetBand.SHOESTRING, "a shoestring budget",
            BudgetBand.MID, "a mid-range budget", BudgetBand.COMFORT, "comfort", BudgetBand.LUXURY, "luxury");

    private static final Map<Pace, String> PACE = Map.of(Pace.PACKED, "packed days", Pace.BALANCED,
            "a balanced pace", Pace.SLOW, "slow days");

    private static final Map<WakeTime, String> WAKE = Map.of(WakeTime.EARLY_BIRD, "an early bird",
            WakeTime.AVERAGE, "somewhere in between", WakeTime.NIGHT_OWL, "a night owl");

    private static final Map<PlanningStyle, String> PLANNING = Map.of(PlanningStyle.DETAILED_ITINERARY,
            "a detailed itinerary", PlanningStyle.ROUGH_PLAN, "a rough plan", PlanningStyle.FULLY_SPONTANEOUS,
            "no fixed plan");

    private static final Map<ExpenseSplitStyle, String> EXPENSES = Map.of(ExpenseSplitStyle.STRICT_SPLIT,
            "splitting every bill exactly", ExpenseSplitStyle.WHOEVER_CAN, "whoever can paying",
            ExpenseSplitStyle.ONE_PAYS_ALTERNATE, "taking turns to pay");

    @Override
    public String id() {
        return NAME;
    }

    @Override
    public List<Topic> topics(VibeDimensions mine, VibeDimensions theirs, String name) {
        List<Topic> topics = new ArrayList<>();
        if (Math.abs(mine.budgetBand().ordinal() - theirs.budgetBand().ordinal()) >= 2) {
            topics.add(new Topic(Dimension.BUDGET,
                    "You plan for %s; %s plans for %s.".formatted(BUDGET.get(mine.budgetBand()), name,
                            BUDGET.get(theirs.budgetBand())),
                    "Agree where you will sleep and roughly what a day costs before anything is booked."));
        }
        if (farApart(mine.pace(), theirs.pace(), Pace.values().length)) {
            topics.add(new Topic(Dimension.PACE,
                    "You like %s; %s likes %s.".formatted(PACE.get(mine.pace()), name, PACE.get(theirs.pace())),
                    "Decide which days are full and which are free, and that splitting up for a day is fine."));
        }
        if (farApart(mine.wakeTime(), theirs.wakeTime(), WakeTime.values().length)) {
            topics.add(new Topic(Dimension.WAKE_TIME,
                    "You are %s; %s is %s.".formatted(WAKE.get(mine.wakeTime()), name, WAKE.get(theirs.wakeTime())),
                    "Agree start times, and what the early riser does while the other sleeps."));
        }
        if (farApart(mine.planningStyle(), theirs.planningStyle(), PlanningStyle.values().length)) {
            topics.add(new Topic(Dimension.PLANNING,
                    "You like %s; %s likes %s.".formatted(PLANNING.get(mine.planningStyle()), name,
                            PLANNING.get(theirs.planningStyle())),
                    "Decide what gets booked ahead and what stays open."));
        }
        if (Math.abs(mine.socialEnergy() - theirs.socialEnergy()) >= SCALE_GAP) {
            topics.add(new Topic(Dimension.SOCIAL_ENERGY,
                    "One of you wants far more company than the other.",
                    "Agree that time on your own is normal, not rude, and how you will say you need it."));
        }
        if (Math.abs(mine.riskAppetite() - theirs.riskAppetite()) >= SCALE_GAP) {
            topics.add(new Topic(Dimension.RISK_APPETITE,
                    "One of you is far more adventurous than the other.",
                    "Name any activity either of you would skip, and agree anyone can sit one out without a debate."));
        }
        if (Math.abs(mine.foodAdventurousness() - theirs.foodAdventurousness()) >= SCALE_GAP) {
            topics.add(new Topic(Dimension.FOOD,
                    "One of you will try far more new food than the other.",
                    "Pick a few places you will both enjoy, so meals are not a negotiation."));
        }
        boolean oneStrict = (mine.expenseSplitStyle() == ExpenseSplitStyle.STRICT_SPLIT)
                != (theirs.expenseSplitStyle() == ExpenseSplitStyle.STRICT_SPLIT);
        if (oneStrict) {
            topics.add(new Topic(Dimension.EXPENSES,
                    "You prefer %s; %s prefers %s.".formatted(EXPENSES.get(mine.expenseSplitStyle()), name,
                            EXPENSES.get(theirs.expenseSplitStyle())),
                    "Agree how costs are split. The trip's settle-up screen keeps track either way."));
        }
        return List.copyOf(topics);
    }

    private static boolean farApart(Enum<?> mine, Enum<?> theirs, int values) {
        return Math.abs(mine.ordinal() - theirs.ordinal()) >= values - 1;
    }
}
