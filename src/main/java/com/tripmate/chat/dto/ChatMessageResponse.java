package com.tripmate.chat.dto;

import com.tripmate.chat.entity.FlagType;
import com.tripmate.chat.entity.MessageKind;
import com.tripmate.chat.entity.SystemCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One message, from the reader's side. No user ID: in a conversation of two, {@code mine} says who wrote it. */
public record ChatMessageResponse(

        UUID id,

        @Schema(description = "1, 2, 3... within the conversation; a gap means a message was missed")
        long seq,

        MessageKind kind,

        boolean mine,

        @Schema(description = "Plain text, already stripped of markup and contact details. Null for a SYSTEM "
                + "message, and for a message whose writer's data has since been erased")
        String body,

        @Schema(description = "SYSTEM messages only")
        SystemCode systemCode,

        @Schema(description = "Contact details were removed from this message before it was stored")
        boolean redacted,

        @Schema(description = "What this message was flagged for, if anything; both people are warned")
        FlagType flag,

        Instant sentAt) {
}
