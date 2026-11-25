package com.tripmate.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the block and report routes. */
public final class ModerationDtos {

    private ModerationDtos() {
    }

    @Schema(description = "Who to block: matchId alone, or tripId with userId of someone who travelled on it with you")
    public record BlockRequest(UUID matchId, UUID tripId, UUID userId) {
    }

    @Schema(description = "Who to report (as for a block), why, and optionally what happened")
    public record ReportRequest(
            UUID matchId, UUID tripId, UUID userId,
            @Schema(description = "HARASSMENT, SCAM_OR_MONEY_REQUEST, FAKE_PROFILE, UNSAFE_BEHAVIOUR or OTHER")
            String reason,
            @Schema(description = "Optional, up to 1000 characters; read only by a moderator") String details) {
    }

    @Schema(description = "A block; the blocked person is shown by first name only")
    public record BlockResponse(UUID id, String firstName, Instant since) {
    }

    public record BlockListResponse(List<BlockResponse> items) {
    }

    @Schema(description = "The report was received. Its outcome is never shared with the reporter.")
    public record ReportResponse(UUID id, String reason, Instant receivedAt) {
    }
}
