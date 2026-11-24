package com.tripmate.chat.dto;

import java.util.List;

/** @param items oldest first */
public record MessagePageResponse(List<ChatMessageResponse> items, boolean hasOlder) {
}
