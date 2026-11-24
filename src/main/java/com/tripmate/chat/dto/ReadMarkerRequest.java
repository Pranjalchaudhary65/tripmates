package com.tripmate.chat.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** @param upToSeq the newest message the reader has seen; a value past the last message stops at it */
public record ReadMarkerRequest(@PositiveOrZero long upToSeq) {
}
