package com.tripmate.chat.dto;

import com.tripmate.chat.entity.FlagType;
import com.tripmate.chat.entity.MessageKind;
import com.tripmate.chat.entity.SystemCode;
import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.trips.entity.TripStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A conversation as its list and its screen header show it. */
public record ConversationSummaryResponse(

        UUID id,

        Trip trip,

        Person otherPerson,

        Role myRole,

        @Schema(description = "False once the conversation is closed or the other person's account is no longer "
                + "active; the history stays readable")
        boolean canSend,

        @Schema(description = "The trip has locked, so the contact endpoint answers")
        boolean contactUnlocked,

        long unreadCount,

        Instant lastMessageAt,

        LastMessage lastMessage,

        Instant createdAt) {

    public enum Role {
        HOST, COMPANION
    }

    public record Trip(UUID id, String title, String destinationName, LocalDate startDate, LocalDate endDate,
                       TripStatus status) {
    }

    /** The other person as a stranger sees them: a first name and a verification tier. */
    public record Person(String firstName, VerificationTier verificationTier) {
    }

    /** @param preview the start of the text, on one line; null for a SYSTEM or unreadable message */
    public record LastMessage(long seq, MessageKind kind, boolean mine, String preview, SystemCode systemCode,
                              FlagType flag) {
    }
}
