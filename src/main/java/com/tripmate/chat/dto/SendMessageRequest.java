package com.tripmate.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param clientMessageId chosen by the client per message and reused on a
 *                        retry, so a message whose response was lost is not
 *                        sent twice
 * @param body            up to 4000 characters as sent; at most 2000 are kept
 *                        once markup is stripped
 */
public record SendMessageRequest(

        @NotNull
        UUID clientMessageId,

        @NotNull
        @Size(max = 4000)
        @Schema(example = "Hi! Are you thinking of a slow trip or a packed one?")
        String body) {
}
