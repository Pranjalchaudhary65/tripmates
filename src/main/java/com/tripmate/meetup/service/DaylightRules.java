package com.tripmate.meetup.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * When a meet-up may start: in daylight at the venue (an hour after sunrise
 * at the earliest, two hours before sunset at the latest, so it also ends in
 * daylight), not sooner than the minimum notice, not further ahead than the
 * maximum, and on a local day before the trip starts. Times are offered and
 * checked on a quarter-hour grid.
 */
@Component
public class DaylightRules {

    static final Duration STEP = Duration.ofMinutes(15);

    private final MeetupProperties properties;

    public DaylightRules(MeetupProperties properties) {
        this.properties = properties;
    }

    /** The allowed start times on one local date; empty when there are none. */
    public Optional<Window> on(LocalDate date, double latitude, double longitude, Instant now, LocalDate tripStart) {
        if (!date.isBefore(tripStart)) {
            return Optional.empty();
        }
        return SolarDaylight.on(date, latitude, longitude).flatMap(sun -> {
            Instant earliest = latest(sun.sunrise().plus(properties.afterSunrise()), now.plus(properties.minNotice()));
            Instant latest = sun.sunset().minus(properties.beforeSunset());
            Instant limit = now.plus(properties.maxAhead());
            if (latest.isAfter(limit)) {
                latest = limit;
            }
            Instant from = ceil(earliest);
            Instant to = floor(latest);
            return from.isAfter(to) ? Optional.empty() : Optional.of(new Window(date, from, to));
        });
    }

    /** The windows from today (in the venue's zone) for the configured number of days. */
    public List<Window> upcoming(double latitude, double longitude, ZoneId zone, Instant now, LocalDate tripStart) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<Window> windows = new ArrayList<>();
        for (int day = 0; day < properties.windowDays(); day++) {
            on(today.plusDays(day), latitude, longitude, now, tripStart).ifPresent(windows::add);
        }
        return windows;
    }

    /** Whether a start time is allowed at the venue, judged on the venue's own calendar date. */
    public boolean allows(Instant start, double latitude, double longitude, ZoneId zone, Instant now,
                          LocalDate tripStart) {
        return neighbours(LocalDate.ofInstant(start, zone)).anyMatch(date ->
                on(date, latitude, longitude, now, tripStart)
                        .filter(window -> !start.isBefore(window.earliest()) && !start.isAfter(window.latest()))
                        .isPresent());
    }

    /** Whether the time is allowed on daylight grounds alone, ignoring notice, horizon and trip start. */
    public boolean isDaylight(Instant start, double latitude, double longitude, ZoneId zone) {
        return neighbours(LocalDate.ofInstant(start, zone)).anyMatch(date ->
                SolarDaylight.on(date, latitude, longitude)
                        .filter(sun -> !start.isBefore(sun.sunrise().plus(properties.afterSunrise()))
                                && !start.isAfter(sun.sunset().minus(properties.beforeSunset())))
                        .isPresent());
    }

    /**
     * A solar day is reckoned from UTC midnight, so where a venue's zone sits
     * far from its longitude, one local date's daylight can belong to the
     * solar day before or after it. All three are checked.
     */
    private static Stream<LocalDate> neighbours(LocalDate date) {
        return Stream.of(date.minusDays(1), date, date.plusDays(1));
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant floor(Instant instant) {
        long step = STEP.getSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), step) * step);
    }

    private static Instant ceil(Instant instant) {
        Instant floored = floor(instant.truncatedTo(ChronoUnit.SECONDS));
        return floored.equals(instant) ? floored : floored.plus(STEP);
    }

    /** Allowed start times on one local date, both ends included. */
    public record Window(LocalDate date, Instant earliest, Instant latest) {
    }
}
