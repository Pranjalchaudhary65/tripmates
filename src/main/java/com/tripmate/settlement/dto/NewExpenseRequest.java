package com.tripmate.settlement.dto;

import com.tripmate.settlement.service.SettlementViews.SplitMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An expense the caller paid for. Amounts are whole paise; they are read as
 * exact decimals so 1250.5 is refused rather than truncated to 1250. The
 * payer is always the caller.
 *
 * @param clientExpenseId chosen by the client once per expense and reused on every retry of it
 * @param participants    who shares the cost; the payer too, if they had a share
 */
public record NewExpenseRequest(

        @NotNull
        UUID clientExpenseId,

        @NotBlank
        @Size(max = 200)
        @Schema(example = "Homestay, two nights")
        String description,

        @NotNull
        @DecimalMin("1")
        @DecimalMax("100000000")
        @Schema(description = "Whole paise", example = "360000")
        BigDecimal amountPaise,

        @NotNull
        SplitMethod split,

        @NotNull
        @Size(min = 1, max = 20)
        List<@NotNull @Valid Participant> participants) {

    /** @param sharePaise EXACT only: this person's share in whole paise; leave out for EQUAL */
    public record Participant(

            @NotNull
            UUID userId,

            @DecimalMin("1")
            @DecimalMax("100000000")
            @Schema(description = "Whole paise", example = "120000")
            BigDecimal sharePaise) {
    }
}
