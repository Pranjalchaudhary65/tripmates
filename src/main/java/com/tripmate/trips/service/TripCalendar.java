package com.tripmate.trips.service;

import com.tripmate.identity.service.AgePolicy;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Today's date for trip rules, in the platform's zone (IST), whatever zone
 * the JVM or the client is in. A trip "starts today" on the same day for
 * every user and every server.
 */
public final class TripCalendar {

    private TripCalendar() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(AgePolicy.LEGAL_ZONE));
    }
}
