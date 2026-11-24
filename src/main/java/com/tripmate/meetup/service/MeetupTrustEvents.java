package com.tripmate.meetup.service;

import com.tripmate.platform.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The only facts the meet-up hands the trust score, through the outbox: a
 * no-show and a change-of-plan cancellation close to the start. Both are
 * about turning up. Nothing here ever carries a comfort rating, a safety
 * cancellation or who stepped back for safety: those have no method on this
 * class at all, so no code path can publish them.
 */
@Component
public class MeetupTrustEvents {

    public static final String AGGREGATE_USER = "USER";

    public static final String MEETUP_NO_SHOW = "MEETUP_NO_SHOW";

    public static final String MEETUP_LATE_CANCEL = "MEETUP_LATE_CANCEL";

    private final OutboxPublisher outbox;

    public MeetupTrustEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void noShow(UUID userId, UUID meetupId) {
        outbox.publish(AGGREGATE_USER, userId, MEETUP_NO_SHOW, Map.of("userId", userId, "meetupId", meetupId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lateConvenienceCancel(UUID userId, UUID meetupId) {
        outbox.publish(AGGREGATE_USER, userId, MEETUP_LATE_CANCEL, Map.of("userId", userId, "meetupId", meetupId));
    }
}
