package com.tripmate.trust.dto;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.trust.service.TrustScoreFormula.InputType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The caller's own trust score and exactly how it was reached. Each
 * component carries its value (0-100), its weight and the points it adds;
 * the points, minus any vouch penalty, add up to the score.
 */
@Schema(description = "The caller's trust score with its breakdown")
public record TrustScoreResponse(
        @Schema(description = "True until the first score has been computed; then score and breakdown are set")
        boolean pending,
        @Schema(description = "0 to 100, two decimals; null while pending")
        BigDecimal score,
        Instant computedAt,
        Breakdown breakdown) {

    public record Breakdown(String formulaVersion, Verification verification, PeerRating peerRating,
                            Completion completion, Reliability reliability, VouchPenalty vouchPenalty) {
    }

    @Schema(description = "Tier points (EMAIL 10, PHONE 25, SELFIE_MATCH 50) plus 2 per active voucher up to 10, "
            + "out of 60")
    public record Verification(VerificationTier tier, int tierPoints, long activeVouchers, int vouchBonus,
                               BigDecimal value, BigDecimal weight, BigDecimal points) {
    }

    @Schema(description = "Visible reviews only, averaged against a 3.5-star prior worth 10 reviews, newer counting more")
    public record PeerRating(int visibleReviews, BigDecimal averageStars, BigDecimal weightedStars, BigDecimal value,
                             BigDecimal weight, BigDecimal points) {
    }

    @Schema(description = "Trips travelled to the end; a new account starts at 50")
    public record Completion(int finishedTrips, BigDecimal value, BigDecimal weight, BigDecimal points) {
    }

    @Schema(description = "100 minus each deduction as it stands today; deductions fade with age")
    public record Reliability(List<Deduction> deductions, BigDecimal value, BigDecimal weight, BigDecimal points) {
    }

    @Schema(description = "detail is the settlement side (PAYER or PAYEE) or the chat flag type, otherwise null")
    public record Deduction(InputType type, String detail, Instant occurredAt, BigDecimal fullPoints,
                            BigDecimal nowPoints) {
    }

    @Schema(description = "Points taken off for vouching for accounts that were later suspended; capped at 9")
    public record VouchPenalty(int count, BigDecimal points) {
    }
}
