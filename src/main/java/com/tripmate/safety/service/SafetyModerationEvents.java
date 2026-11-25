package com.tripmate.safety.service;

import com.tripmate.platform.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * What live trip safety hands the moderation queue, through the outbox: an
 * SOS was raised. That is all it ever publishes. Safety actions are always
 * free (DESIGN.md, Trust Score Engine, safety carve-out): there is no trust
 * score event here, and no class in this module could send one.
 */
@Component
public class SafetyModerationEvents {

    public static final String AGGREGATE_SAFETY_REPORT = "SAFETY_REPORT";

    public static final String SOS_RAISED = "SAFETY_SOS_RAISED";

    private final OutboxPublisher outbox;

    public SafetyModerationEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sosRaised(UUID sosId, UUID tripId, UUID userId) {
        outbox.publish(AGGREGATE_SAFETY_REPORT, sosId, SOS_RAISED,
                Map.of("sosId", sosId, "tripId", tripId, "userId", userId));
    }
}
