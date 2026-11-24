package com.tripmate.meetup.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Checked against published sunrise and sunset tables, to within a few minutes. */
class SolarDaylightTest {

    private static final Duration TOLERANCE = Duration.ofMinutes(4);

    @Test
    void bengaluruOnTheJuneSolstice() {
        SolarDaylight.Window window = SolarDaylight.on(LocalDate.of(2026, 6, 21), 12.9716, 77.5946).orElseThrow();

        assertNear(window.sunrise(), Instant.parse("2026-06-21T00:24:00Z"));
        assertNear(window.sunset(), Instant.parse("2026-06-21T13:17:00Z"));
    }

    @Test
    void delhiOnTheDecemberSolstice() {
        SolarDaylight.Window window = SolarDaylight.on(LocalDate.of(2026, 12, 21), 28.6139, 77.209).orElseThrow();

        assertNear(window.sunrise(), Instant.parse("2026-12-21T01:40:00Z"));
        assertNear(window.sunset(), Instant.parse("2026-12-21T11:58:00Z"));
    }

    @Test
    void noWindowWhereTheSunDoesNotSet() {
        assertThat(SolarDaylight.on(LocalDate.of(2026, 6, 21), 78.22, 15.65)).isEmpty();
        assertThat(SolarDaylight.on(LocalDate.of(2026, 12, 21), 78.22, 15.65)).isEmpty();
    }

    private static void assertNear(Instant actual, Instant expected) {
        assertThat(Duration.between(expected, actual).abs()).as("%s vs %s", actual, expected)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
