package com.tripmate.vibe.dto;

import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.VibeDimensions;
import com.tripmate.vibe.entity.WakeTime;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** All eight dimensions, set by hand after the quiz. */
public record VibeAdjustRequest(

        @NotNull BudgetBand budgetBand,

        @NotNull Pace pace,

        @NotNull WakeTime wakeTime,

        @NotNull PlanningStyle planningStyle,

        @NotNull @Min(VibeDimensions.SCALE_MIN) @Max(VibeDimensions.SCALE_MAX) Integer socialEnergy,

        @NotNull @Min(VibeDimensions.SCALE_MIN) @Max(VibeDimensions.SCALE_MAX) Integer riskAppetite,

        @NotNull @Min(VibeDimensions.SCALE_MIN) @Max(VibeDimensions.SCALE_MAX) Integer foodAdventurousness,

        @NotNull ExpenseSplitStyle expenseSplitStyle) {

    public VibeDimensions toDimensions() {
        return new VibeDimensions(budgetBand, pace, wakeTime, planningStyle, socialEnergy, riskAppetite,
                foodAdventurousness, expenseSplitStyle);
    }
}
