package com.tripmate.vibe.entity;

import java.util.Objects;

/**
 * The eight dimensions of a vibe profile (DESIGN.md §2), each named so the
 * match explanation can say which ones agree. The three 1-5 scales are
 * checked here as well as by the database.
 */
public record VibeDimensions(BudgetBand budgetBand, Pace pace, WakeTime wakeTime, PlanningStyle planningStyle,
                             int socialEnergy, int riskAppetite, int foodAdventurousness,
                             ExpenseSplitStyle expenseSplitStyle) {

    public static final int SCALE_MIN = 1;

    public static final int SCALE_MAX = 5;

    public VibeDimensions {
        Objects.requireNonNull(budgetBand, "budgetBand");
        Objects.requireNonNull(pace, "pace");
        Objects.requireNonNull(wakeTime, "wakeTime");
        Objects.requireNonNull(planningStyle, "planningStyle");
        Objects.requireNonNull(expenseSplitStyle, "expenseSplitStyle");
        requireScale(socialEnergy, "socialEnergy");
        requireScale(riskAppetite, "riskAppetite");
        requireScale(foodAdventurousness, "foodAdventurousness");
    }

    private static void requireScale(int value, String name) {
        if (value < SCALE_MIN || value > SCALE_MAX) {
            throw new IllegalArgumentException(name + " must be between 1 and 5");
        }
    }
}
