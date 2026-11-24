package com.tripmate.trips.dto;

import com.tripmate.trips.entity.Trip;
import com.tripmate.trips.entity.TripDetails;
import com.tripmate.vibe.entity.BudgetBand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Shape and range rules only; the date rules need the clock and live in the
 * service. Text is refused if it holds control characters or the Unicode
 * marks that reverse the direction of text, which could make a title read
 * differently on screen from what was stored; the description may still
 * have line breaks and tabs.
 */
public record CreateTripRequest(

        @Schema(example = "Monsoon weekend in Gokarna")
        @NotBlank @Size(min = 3, max = 140)
        @Pattern(regexp = SINGLE_LINE, message = "must not contain control characters")
        String title,

        @Schema(example = "Beach hopping from Kudle to Paradise, easy pace, hostel stays.")
        @NotBlank @Size(max = 4000)
        @Pattern(regexp = MULTI_LINE, message = "must not contain control characters")
        String description,

        @Schema(example = "Gokarna, Karnataka")
        @NotBlank @Size(max = 120)
        @Pattern(regexp = SINGLE_LINE, message = "must not contain control characters")
        String destinationName,

        @Schema(example = "14.5479")
        @NotNull @DecimalMin("-90") @DecimalMax("90")
        Double latitude,

        @Schema(example = "74.3188")
        @NotNull @DecimalMin("-180") @DecimalMax("180")
        Double longitude,

        @Schema(description = "How far around the destination the trip roams; 0 for one place", example = "25")
        @DecimalMin("0") @DecimalMax("500") @Digits(integer = 3, fraction = 2)
        BigDecimal radiusKm,

        @Schema(description = "From today (IST) to a year ahead", example = "2026-12-12")
        @NotNull
        LocalDate startDate,

        @Schema(description = "At most 60 days after the start", example = "2026-12-14")
        @NotNull
        LocalDate endDate,

        @Schema(description = "Companions wanted, not counting the host", example = "3")
        @NotNull @Min(1) @Max(Trip.MAX_SLOTS)
        Integer totalSlots,

        @NotNull
        BudgetBand budgetBand) {

    static final String SINGLE_LINE = "[^\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]*";

    static final String MULTI_LINE = "(?:[\\r\\n\\t]|[^\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069])*";

    public TripDetails toDetails() {
        return new TripDetails(title.strip(), description.strip(), destinationName.strip(), latitude, longitude,
                radiusKm == null ? BigDecimal.ZERO : radiusKm, startDate, endDate, totalSlots, budgetBand);
    }
}
