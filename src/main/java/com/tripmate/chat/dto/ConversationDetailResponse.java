package com.tripmate.chat.dto;

import java.util.List;

/**
 * The conversation screen in one call.
 *
 * @param messages the newest page, oldest first
 * @param hasOlder whether older messages can be fetched with {@code before}
 */
public record ConversationDetailResponse(ConversationSummaryResponse conversation,
                                         List<ChatMessageResponse> messages, boolean hasOlder) {
}
