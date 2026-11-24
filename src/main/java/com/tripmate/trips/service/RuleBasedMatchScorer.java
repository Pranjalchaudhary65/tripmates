package com.tripmate.trips.service;

import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The default scorer: a weighted sum of how close each dimension is, with
 * fixed sentences for the dimensions that clearly agree or clearly do not.
 * Deterministic, so the same two profiles always get the same score and the
 * same reasons, and it needs nothing outside this class.
 *
 * <p>Each dimension scores a closeness from 0 to 1. Ordered values (budget,
 * pace, wake time, planning) score 1 minus their distance over the widest
 * possible distance; the 1-5 scales likewise. Expense styles have no order:
 * the same style is 1, "whoever can" against "take turns" is 1/2 (both are
 * relaxed about exact amounts), and "split exactly" against either is 0.
 * Closeness of 2/3 or more is a match, 1/3 or less a mismatch, and anything
 * between is left out of the explanation rather than called either.
 *
 * <p>Weights sum to 100, so the score is the weighted closeness rounded half
 * up. Budget weighs most because it decides where everyone sleeps and eats;
 * food least because it is the easiest to work around on the day.
 */
@Component
@ConditionalOnProperty(prefix = "tripmate.matching", name = "scorer", havingValue = RuleBasedMatchScorer.NAME,
        matchIfMissing = true)
public class RuleBasedMatchScorer implements MatchScorer {

    /** The value of tripmate.matching.scorer that selects this scorer. */
    public static final String NAME = "rule-based";

    static final Map<Dimension, Integer> WEIGHTS = weights();

    private static final Comparator<Part> HEAVIEST_FIRST = Comparator
            .comparing((Part part) -> WEIGHTS.get(part.dimension())).reversed()
            .thenComparing(Part::dimension);

    private static final Map<BudgetBand, String> BUDGET = Map.of(BudgetBand.SHOESTRING, "shoestring",
            BudgetBand.MID, "mid-range", BudgetBand.COMFORT, "comfort", BudgetBand.LUXURY, "luxury");

    private static final Map<Pace, String> PACE = Map.of(Pace.PACKED, "packed days", Pace.BALANCED,
            "a balanced pace", Pace.SLOW, "slow travel");

    private static final Map<WakeTime, String> WAKE = Map.of(WakeTime.EARLY_BIRD, "an early bird",
            WakeTime.AVERAGE, "somewhere in between", WakeTime.NIGHT_OWL, "a night owl");

    private static final Map<WakeTime, String> BOTH_WAKE = Map.of(WakeTime.EARLY_BIRD, "You are both early birds.",
            WakeTime.AVERAGE, "You both keep ordinary hours.", WakeTime.NIGHT_OWL, "You are both night owls.");

    private static final Map<PlanningStyle, String> PLANNING = Map.of(PlanningStyle.DETAILED_ITINERARY,
            "a detailed itinerary", PlanningStyle.ROUGH_PLAN, "a rough plan", PlanningStyle.FULLY_SPONTANEOUS,
            "no fixed plan");

    private static final Map<ExpenseSplitStyle, String> EXPENSES = Map.of(ExpenseSplitStyle.STRICT_SPLIT,
            "splitting every bill exactly", ExpenseSplitStyle.WHOEVER_CAN, "whoever can paying",
            ExpenseSplitStyle.ONE_PAYS_ALTERNATE, "taking turns to pay");

    @Override
    public String id() {
        return NAME + "-1";
    }

    @Override
    public MatchResult score(VibeDimensions viewer, VibeDimensions host, BudgetBand tripBudget) {
        List<Part> parts = List.of(
                budget(viewer.budgetBand(), tripBudget),
                ordered(Dimension.PACE, viewer.pace(), host.pace(), Pace.values().length,
                        "You both like " + PACE.get(viewer.pace()) + ".",
                        "You like " + PACE.get(viewer.pace()) + "; the host likes " + PACE.get(host.pace()) + "."),
                ordered(Dimension.WAKE_TIME, viewer.wakeTime(), host.wakeTime(), WakeTime.values().length,
                        BOTH_WAKE.get(viewer.wakeTime()),
                        "You are " + WAKE.get(viewer.wakeTime()) + "; the host is " + WAKE.get(host.wakeTime()) + "."),
                ordered(Dimension.PLANNING, viewer.planningStyle(), host.planningStyle(),
                        PlanningStyle.values().length,
                        "You both like " + PLANNING.get(viewer.planningStyle()) + ".",
                        "You like " + PLANNING.get(viewer.planningStyle()) + "; the host likes "
                                + PLANNING.get(host.planningStyle()) + "."),
                scale(Dimension.SOCIAL_ENERGY, viewer.socialEnergy(), host.socialEnergy(), "social energy",
                        "You are much more social than the host.", "The host is much more social than you."),
                scale(Dimension.RISK_APPETITE, viewer.riskAppetite(), host.riskAppetite(), "appetite for risk",
                        "You are much more adventurous than the host.",
                        "The host is much more adventurous than you."),
                scale(Dimension.FOOD, viewer.foodAdventurousness(), host.foodAdventurousness(),
                        "taste for trying new food", "You will try far more new food than the host.",
                        "The host will try far more new food than you."),
                expenses(viewer.expenseSplitStyle(), host.expenseSplitStyle()));

        BigDecimal total = BigDecimal.ZERO;
        List<Reason> matches = new ArrayList<>();
        List<Reason> mismatches = new ArrayList<>();
        for (Part part : parts.stream().sorted(HEAVIEST_FIRST).toList()) {
            total = total.add(BigDecimal.valueOf((long) WEIGHTS.get(part.dimension()) * part.closeness())
                    .divide(BigDecimal.valueOf(part.outOf()), 6, RoundingMode.HALF_UP));
            if (part.isMatch()) {
                matches.add(new Reason(part.dimension(), part.matchLabel()));
            } else if (part.isMismatch()) {
                mismatches.add(new Reason(part.dimension(), part.mismatchLabel()));
            }
        }
        return new MatchResult(total.setScale(0, RoundingMode.HALF_UP).intValueExact(), matches, mismatches);
    }

    private static Part budget(BudgetBand viewer, BudgetBand trip) {
        int widest = BudgetBand.values().length - 1;
        int distance = Math.abs(viewer.ordinal() - trip.ordinal());
        String match = distance == 0
                ? "The trip's budget is " + BUDGET.get(trip) + ", the same as yours."
                : "The trip's budget is " + BUDGET.get(trip) + ", close to your " + BUDGET.get(viewer) + ".";
        return new Part(Dimension.BUDGET, widest - distance, widest, match,
                "The trip's budget is " + BUDGET.get(trip) + "; yours is " + BUDGET.get(viewer) + ".");
    }

    private static Part ordered(Dimension dimension, Enum<?> viewer, Enum<?> host, int values, String match,
                                String mismatch) {
        int widest = values - 1;
        return new Part(dimension, widest - Math.abs(viewer.ordinal() - host.ordinal()), widest, match, mismatch);
    }

    private static Part scale(Dimension dimension, int viewer, int host, String quality, String viewerMore,
                              String hostMore) {
        int widest = VibeDimensions.SCALE_MAX - VibeDimensions.SCALE_MIN;
        String match = viewer == host ? "You have the same " + quality + "." : "You have a similar " + quality + ".";
        return new Part(dimension, widest - Math.abs(viewer - host), widest, match,
                viewer > host ? viewerMore : hostMore);
    }

    private static Part expenses(ExpenseSplitStyle viewer, ExpenseSplitStyle host) {
        int closeness;
        if (viewer == host) {
            closeness = 2;
        } else if (viewer != ExpenseSplitStyle.STRICT_SPLIT && host != ExpenseSplitStyle.STRICT_SPLIT) {
            closeness = 1;
        } else {
            closeness = 0;
        }
        return new Part(Dimension.EXPENSES, closeness, 2, "You both prefer " + EXPENSES.get(viewer) + ".",
                "You prefer " + EXPENSES.get(viewer) + "; the host prefers " + EXPENSES.get(host) + ".");
    }

    private static Map<Dimension, Integer> weights() {
        Map<Dimension, Integer> weights = new EnumMap<>(Dimension.class);
        weights.put(Dimension.BUDGET, 20);
        weights.put(Dimension.PACE, 15);
        weights.put(Dimension.SOCIAL_ENERGY, 15);
        weights.put(Dimension.EXPENSES, 15);
        weights.put(Dimension.WAKE_TIME, 10);
        weights.put(Dimension.PLANNING, 10);
        weights.put(Dimension.RISK_APPETITE, 10);
        weights.put(Dimension.FOOD, 5);
        if (weights.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new IllegalStateException("Match weights must sum to 100");
        }
        return Map.copyOf(weights);
    }

    /** Closeness as the exact fraction closeness/outOf, so the thresholds below are compared without rounding. */
    private record Part(Dimension dimension, int closeness, int outOf, String matchLabel, String mismatchLabel) {

        boolean isMatch() {
            return closeness * 3 >= outOf * 2;
        }

        boolean isMismatch() {
            return closeness * 3 <= outOf;
        }
    }
}
