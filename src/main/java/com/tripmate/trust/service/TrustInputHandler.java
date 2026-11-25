package com.tripmate.trust.service;

import com.tripmate.platform.outbox.DeliveredEvent;
import com.tripmate.platform.outbox.OutboxHandler;
import com.tripmate.trust.repository.TrustQueries;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The trust module's door: the outbox events that may move someone's score,
 * by exact name, and nothing else. The publishing modules are never imported;
 * the names are their published contract, and a test holds each one to the
 * constant its publisher uses.
 *
 * <p>Deliberately absent: anything from live trip safety (an SOS, a missed
 * check-in), anything about a comfort rating or a safety cancellation, and
 * settlement disputes. They have no name here, so the relay never shows them
 * to this handler, and {@code ck_trust_events_type} would refuse them anyway.
 *
 * <p>Every accepted event is about the user its aggregate names. It is
 * recorded once (a redelivery is ignored by the unique source ID) and that
 * user's score is queued for recompute; the score itself is never computed
 * here, in the relay's transaction.
 */
@Component
public class TrustInputHandler implements OutboxHandler {

    static final String AGGREGATE_USER = "USER";

    /** Each type, and the payload field (if any) that is its detail. */
    static final Map<String, String> DETAIL_FIELD = Map.of(
            "VERIFICATION_TIER_CHANGE", "to",
            "VOUCH_GIVEN", "",
            "VOUCH_REVOKED", "",
            "VOUCH_PENALIZED", "",
            "MEETUP_NO_SHOW", "",
            "MEETUP_LATE_CANCEL", "",
            "SETTLEMENT_OVERDUE", "role",
            "CHAT_MESSAGE_FLAGGED", "flagType");

    /** The payload field naming the thing the event was about, kept for a dispute; never shown to anyone. */
    private static final List<String> RELATED_FIELDS = List.of("meetupId", "tripId", "messageId", "vouchId",
            "vouchedUserId");

    private static final Pattern DETAIL = Pattern.compile("[A-Z_]{1,30}");

    private final TrustQueries queries;

    private final JsonMapper json;

    private final Clock clock;

    public TrustInputHandler(TrustQueries queries, JsonMapper json, Clock clock) {
        this.queries = queries;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Set<String> eventTypes() {
        return DETAIL_FIELD.keySet();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DeliveredEvent event) {
        if (!AGGREGATE_USER.equals(event.aggregateType())) {
            throw new IllegalArgumentException("Trust input " + event.eventType() + " is not about a user");
        }
        JsonNode payload = json.readTree(event.payload());
        String detail = detail(event.eventType(), payload);
        queries.recordEvent(event.aggregateId(), event.eventType(), detail, related(payload), event.id(),
                event.createdAt());
        queries.markDirty(List.of(event.aggregateId()), clock.instant());
    }

    private static String detail(String type, JsonNode payload) {
        String field = DETAIL_FIELD.get(type);
        if (field.isEmpty()) {
            return null;
        }
        JsonNode value = payload.get(field);
        if (value == null || !value.isString() || !DETAIL.matcher(value.asString()).matches()) {
            throw new IllegalArgumentException(type + " has no readable " + field);
        }
        return value.asString();
    }

    private static UUID related(JsonNode payload) {
        for (String field : RELATED_FIELDS) {
            JsonNode value = payload.get(field);
            if (value != null && value.isString()) {
                try {
                    return UUID.fromString(value.asString());
                } catch (IllegalArgumentException notAnId) {
                    return null;
                }
            }
        }
        return null;
    }
}
