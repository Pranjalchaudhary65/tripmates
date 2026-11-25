package com.tripmate.meetup.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DaylightRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final double LAT = 12.9716;

    private static final double LON = 77.5946;

    /** 08:00 IST on 1 Dec 2026. */
    private static final Instant NOW = Instant.parse("2026-12-01T02:30:00Z");

    private static final LocalDate TRIP_START = LocalDate.of(2026, 12, 10);

    private final DaylightRules rules = new DaylightRules(properties());

    @Test
    void aWindowStartsAnHourAfterSunriseAndEndsTwoHoursBeforeSunsetOnAQuarterHourGrid() {
        SolarDaylight.Window sun = SolarDaylight.on(LocalDate.of(2026, 12, 3), LAT, LON).orElseThrow();
        DaylightRules.Window window = rules.on(LocalDate.of(2026, 12, 3), LAT, LON, NOW, TRIP_START).orElseThrow();

        assertThat(window.earliest()).isAfterOrEqualTo(sun.sunrise().plus(Duration.ofHours(1)))
                .isBefore(sun.sunrise().plus(Duration.ofMinutes(76)));
        assertThat(window.latest()).isBeforeOrEqualTo(sun.sunset().minus(Duration.ofHours(2)))
                .isAfter(sun.sunset().minus(Duration.ofMinutes(136)));
        assertThat(window.earliest().getEpochSecond() % 900).isZero();
        assertThat(window.latest().getEpochSecond() % 900).isZero();
    }

    @Test
    void todaysWindowRespectsTheMinimumNotice() {
        DaylightRules.Window today = rules.on(LocalDate.of(2026, 12, 1), LAT, LON, NOW, TRIP_START).orElseThrow();

        assertThat(today.earliest()).isAfterOrEqualTo(NOW.plus(Duration.ofHours(3)));
    }

    @Test
    void noWindowOnOrAfterTheTripStarts() {
        List<DaylightRules.Window> windows = rules.upcoming(LAT, LON, IST, NOW, TRIP_START);

        assertThat(windows).isNotEmpty();
        assertThat(windows).allSatisfy(window -> assertThat(window.date()).isBefore(TRIP_START));
        assertThat(rules.on(TRIP_START, LAT, LON, NOW, TRIP_START)).isEmpty();
    }

    @Test
    void nightIsNeverDaylightAndAnOfferedTimeIsAllowed() {
        Instant midnightIst = Instant.parse("2026-12-03T18:30:00Z");
        DaylightRules.Window window = rules.on(LocalDate.of(2026, 12, 3), LAT, LON, NOW, TRIP_START).orElseThrow();

        assertThat(rules.isDaylight(midnightIst, LAT, LON, IST)).isFalse();
        assertThat(rules.allows(window.earliest(), LAT, LON, IST, NOW, TRIP_START)).isTrue();
        assertThat(rules.allows(window.latest(), LAT, LON, IST, NOW, TRIP_START)).isTrue();
        assertThat(rules.allows(window.latest().plusSeconds(60), LAT, LON, IST, NOW, TRIP_START)).isFalse();
    }

    @Test
    void aVenueWhoseZoneIsFarFromItsLongitudeStillAcceptsTheTimesItOffers() {
        ZoneId utc = ZoneId.of("UTC");
        for (DaylightRules.Window window : rules.upcoming(-35, 170, utc, NOW, TRIP_START)) {
            assertThat(rules.allows(window.earliest(), -35, 170, utc, NOW, TRIP_START)).as("%s", window).isTrue();
        }
    }

    static MeetupProperties properties() {
        RateLimit limit = new RateLimit(100, Duration.ofHours(1));
        return new MeetupProperties(limit, limit, 25, Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3),
                Duration.ofDays(30), 14, Duration.ofMinutes(30), Duration.ofMinutes(90), Duration.ofMinutes(30),
                Duration.ofMinutes(60), 3, Duration.ofHours(24), Duration.ofDays(7), Duration.ofMinutes(1), 100, 150);
    }
}
