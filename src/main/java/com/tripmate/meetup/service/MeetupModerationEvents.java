package com.tripmate.meetup.service;

import com.tripmate.platform.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What the meet-up hands the moderation queue, through the outbox: someone
 * called a meet-up off for safety. The flag says who, which meet-up and trip,
 * and when, and nothing else. It never carries a comfort rating, a note or a
 * reason, and its one method takes no parameter that could hold one. A match
 * stopped by a low comfort rating raises no flag at all: "who stepped back"
 * after a completed meet-up would itself tell a moderator how they rated it.
 *
 * <p>This is not a trust input: the trust module's handler does not accept
 * the event type, so stepping back for safety stays free.
 */
@Component
public class MeetupModerationEvents {

    public static final String AGGREGATE_SAFETY_REPORT = "SAFETY_REPORT";

    public static final String SAFETY_MEETUP_CANCELLED = "SAFETY_MEETUP_CANCELLED";

    private final OutboxPublisher outbox;

    public MeetupModerationEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void safetyCancellation(UUID cancelledBy, UUID meetupId, UUID tripId, Instant cancelledAt) {
        outbox.publish(AGGREGATE_SAFETY_REPORT, meetupId, SAFETY_MEETUP_CANCELLED, Map.of(
                "userId", cancelledBy, "meetupId", meetupId, "tripId", tripId,
                "cancelledAt", cancelledAt.toString()));
    }
}
