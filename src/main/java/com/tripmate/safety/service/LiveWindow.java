package com.tripmate.safety.service;

import com.tripmate.identity.service.AgePolicy;
import com.tripmate.safety.service.LiveTripViews.Phase;
import com.tripmate.trips.service.TripCrew.Crew;

import java.time.Instant;
import java.time.LocalTime;

/**
 * When a trip is live, in the platform's zone (IST): from midnight on its
 * first day to noon on the day after its last, which covers the journey
 * home. Check-ins are never scheduled outside it.
 */
final class LiveWindow {

    private static final LocalTime HOME_BY = LocalTime.NOON;

    private LiveWindow() {
    }

    static Instant start(Crew crew) {
        return crew.startDate().atStartOfDay(AgePolicy.LEGAL_ZONE).toInstant();
    }

    static Instant end(Crew crew) {
        return crew.endDate().plusDays(1).atTime(HOME_BY).atZone(AgePolicy.LEGAL_ZONE).toInstant();
    }

    static Phase phase(Crew crew, Instant now) {
        if (now.isBefore(start(crew))) {
            return Phase.BEFORE;
        }
        return now.isBefore(end(crew)) ? Phase.ACTIVE : Phase.ENDED;
    }

    /** The next check-in after {@code from}, or null when it would fall after the trip. */
    static Instant nextDue(Crew crew, Instant from, java.time.Duration interval) {
        Instant base = from.isBefore(start(crew)) ? start(crew) : from;
        Instant due = base.plus(interval);
        return due.isBefore(end(crew)) ? due : null;
    }
}
