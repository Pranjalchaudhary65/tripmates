package com.tripmate.chat.dto;

import java.util.List;

/** Opening lines the caller may send as their own message. Nothing is sent for them. */
public record IcebreakersResponse(List<String> suggestions) {
}
