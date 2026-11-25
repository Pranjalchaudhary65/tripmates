package com.tripmate.identity.emergency;

import com.tripmate.identity.service.EmergencyContactService.AlertContact;

/**
 * Tells someone's emergency contact that they may need help: they went quiet
 * at a meet-up, missed a check-in on a trip, or pressed SOS. One sender for
 * every safety path, so there is one emergency contact and one way of
 * reaching them. Pluggable like the SMS and liveness providers: the demo
 * default needs no account, and a real SMS gateway is a new implementation
 * selected by {@code tripmate.identity.emergency-alert-sender}.
 *
 * <p>An implementation throws if it could not deliver, so the caller leaves
 * the alert unrecorded and its job tries again on the next run.
 */
public interface EmergencyAlertSender {

    /** The value of tripmate.identity.emergency-alert-sender that selects this implementation. */
    String id();

    void send(AlertContact contact, Alert alert);

    /**
     * @param userEmail where the mailbox implementation delivers its copy
     * @param situation what happened, where and when, in one or two plain
     *                  sentences composed by the module raising the alert
     *                  from fixed wording; it goes into an SMS as it is
     */
    record Alert(String userFirstName, String userEmail, String situation) {
    }
}
