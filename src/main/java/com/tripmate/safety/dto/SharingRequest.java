package com.tripmate.safety.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param checkInEveryHours 3, 6, 12 or 24
 * @param note              optional, for the emergency contact only: where the traveller is staying, who to ask for
 */
public record SharingRequest(

        @NotNull
        @Schema(example = "12")
        Integer checkInEveryHours,

        @Size(max = 400)
        @Schema(example = "Staying at Namaste Cafe guesthouse, Om Beach")
        String note) {
}
