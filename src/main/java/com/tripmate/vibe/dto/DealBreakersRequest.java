package com.tripmate.vibe.dto;

import com.tripmate.vibe.entity.DealBreakers;
import com.tripmate.vibe.entity.GenderPreference;
import com.tripmate.vibe.entity.HabitPreference;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DealBreakersRequest(

        @Schema(description = "NO: a trip without smoking. OK: smoking will be part of my trip. EITHER: no preference")
        @NotNull HabitPreference smoking,

        @Schema(description = "NO: a trip without drinking. OK: drinking will be part of my trip. "
                + "EITHER: no preference")
        @NotNull HabitPreference drinking,

        @Schema(description = "SAME: only travel with people of my own gender")
        @NotNull GenderPreference genderPreference,

        @Schema(description = "Only match with people whose trust score is at least this", example = "40")
        @NotNull @Min(0) @Max(DealBreakers.MAX_TRUST_SCORE) Integer minTrustScore) {
}
