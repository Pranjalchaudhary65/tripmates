package com.tripmate.meetup.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * The meet-up's timing rules and limits. The geofence radius is not here: it
 * is fixed at {@link #GEOFENCE_METERS}, and a CHECK constraint holds the
 * database to it, so no configuration can quietly widen it.
 *
 * @param writeLimit          proposals, confirmations, cancellations, check-ins and feedback, per account
 * @param readLimit           the match screen and venue searches, per account
 * @param venueRadiusKm       default search radius around a point when the client gives none
 * @param afterSunrise        the earliest start: this long after sunrise at the venue
 * @param beforeSunset        the latest start: this long before sunset, so the meet-up ends in daylight
 * @param minNotice           the soonest a meet-up may start after it is proposed
 * @param maxAhead            the furthest ahead one may be proposed
 * @param windowDays          how many days of daylight windows a venue list offers
 * @param checkinOpensBefore  check-in opens this long before the start
 * @param checkinClosesAfter  and closes this long after; a confirmed meet-up not completed by then is a no-show
 * @param pingAfter           someone not accounted for this long after the start is asked if they are safe
 * @param escalateAfter       and their emergency contact is alerted after this long
 * @param comfortThreshold    a comfort rating below this stops the match
 * @param lateCancelWithin    a change-of-plan cancellation this close to the start is a trust input
 * @param selfieRetention     check-in selfies are deleted this long after they are taken
 * @param jobInterval         how often the safety and purge jobs run
 * @param jobBatch            meet-ups or selfies one job run handles at most
 * @param maxLocationAccuracyMeters a reported location rougher than this cannot check in
 */
@ConfigurationProperties("tripmate.meetup")
public record MeetupProperties(RateLimit writeLimit, RateLimit readLimit, double venueRadiusKm,
                               Duration afterSunrise, Duration beforeSunset, Duration minNotice, Duration maxAhead,
                               int windowDays, Duration checkinOpensBefore, Duration checkinClosesAfter,
                               Duration pingAfter, Duration escalateAfter, int comfortThreshold,
                               Duration lateCancelWithin, Duration selfieRetention, Duration jobInterval, int jobBatch,
                               double maxLocationAccuracyMeters) {

    /** The geofence: a check-in counts only within this many metres of the venue. */
    public static final int GEOFENCE_METERS = 100;

    /** The widest venue search a client may ask for. */
    public static final double MAX_VENUE_RADIUS_KM = 100;

    public MeetupProperties {
        String prefix = "tripmate.meetup.";
        Objects.requireNonNull(writeLimit, prefix + "write-limit is required");
        Objects.requireNonNull(readLimit, prefix + "read-limit is required");
        for (Duration duration : new Duration[] {afterSunrise, beforeSunset, minNotice, maxAhead, checkinOpensBefore,
                checkinClosesAfter, pingAfter, escalateAfter, lateCancelWithin, selfieRetention, jobInterval}) {
            Objects.requireNonNull(duration, prefix + " timings are all required");
        }
        if (!escalateAfter.minus(pingAfter).isPositive() || !checkinClosesAfter.minus(escalateAfter).isPositive()) {
            throw new IllegalArgumentException(prefix + "ping-after < escalate-after < checkin-closes-after must hold");
        }
        if (comfortThreshold < 2 || comfortThreshold > 5) {
            throw new IllegalArgumentException(prefix + "comfort-threshold must be 2 to 5");
        }
        if (venueRadiusKm <= 0 || venueRadiusKm > MAX_VENUE_RADIUS_KM || windowDays < 1 || jobBatch < 1
                || maxLocationAccuracyMeters < GEOFENCE_METERS) {
            throw new IllegalArgumentException(prefix + "radius, window-days, job-batch or accuracy out of range");
        }
    }
}
