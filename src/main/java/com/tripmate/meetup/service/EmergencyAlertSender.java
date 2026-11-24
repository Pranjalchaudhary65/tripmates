package com.tripmate.meetup.service;

import com.tripmate.identity.service.EmergencyContactService.AlertContact;

import java.time.Instant;

/**
 * Tells someone's emergency contact that they went quiet at a meet-up.
 * Pluggable like the SMS and liveness providers: the demo default needs no
 * account, and a real SMS gateway is a new implementation selected by
 * {@code tripmate.meetup.alert-sender}.
 *
 * <p>An implementation throws if it could not deliver, so the safety job
 * leaves the alert unrecorded and tries again on its next run.
 */
public interface EmergencyAlertSender {

    /** The value of tripmate.meetup.alert-sender that selects this implementation. */
    String id();

    void send(AlertContact contact, Alert alert);

    /**
     * Only what the contact needs to act: who, where, when. No trip details,
     * nothing about the other person.
     *
     * @param userEmail   where the mailbox implementation delivers its copy
     */
    record Alert(String userFirstName, String userEmail, String venueName, String venueAddress, Instant scheduledAt) {
    }
}
