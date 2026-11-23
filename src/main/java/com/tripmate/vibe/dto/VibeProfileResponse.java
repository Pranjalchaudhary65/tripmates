package com.tripmate.vibe.dto;

import com.tripmate.vibe.entity.BudgetBand;
import com.tripmate.vibe.entity.ExpenseSplitStyle;
import com.tripmate.vibe.entity.GenderPreference;
import com.tripmate.vibe.entity.HabitPreference;
import com.tripmate.vibe.entity.Pace;
import com.tripmate.vibe.entity.PlanningStyle;
import com.tripmate.vibe.entity.WakeTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** The caller's vibe profile and deal-breakers in one call. Never includes the raw quiz answers. */
public record VibeProfileResponse(

        @Schema(description = "Null until the quiz is finished")
        Profile profile,

        @Schema(description = "Null until they are set")
        DealBreakersView dealBreakers) {

    public record Profile(

            BudgetBand budgetBand,

            Pace pace,

            WakeTime wakeTime,

            PlanningStyle planningStyle,

            @Schema(description = "1 (quiet) to 5 (very social)")
            int socialEnergy,

            @Schema(description = "1 (cautious) to 5 (adventurous)")
            int riskAppetite,

            @Schema(description = "1 (familiar food) to 5 (anything once)")
            int foodAdventurousness,

            ExpenseSplitStyle expenseSplitStyle,

            @Schema(description = "When the user last changed a dimension by hand; null if as the quiz scored it")
            Instant adjustedAt,

            Instant updatedAt) {
    }

    public record DealBreakersView(HabitPreference smoking, HabitPreference drinking,
                                   GenderPreference genderPreference, int minTrustScore, Instant updatedAt) {
    }
}
