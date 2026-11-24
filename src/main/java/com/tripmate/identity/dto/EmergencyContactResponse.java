package com.tripmate.identity.dto;

import com.tripmate.identity.entity.EmergencyContact.Relationship;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** @param contact null when none is set */
public record EmergencyContactResponse(Contact contact) {

    /** @param maskedPhone enough to recognise the number, not to dial it; null if it can no longer be read */
    public record Contact(String name, Relationship relationship,
                          @Schema(example = "+91 ••••••• 210") String maskedPhone, Instant updatedAt) {
    }
}
