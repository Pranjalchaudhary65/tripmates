package com.tripmate.identity.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AgePolicyTest {

    /** 2026-11-08 12:00 in IST. */
    private static final Clock NOON_IST = Clock.fixed(Instant.parse("2026-11-08T06:30:00Z"), ZoneOffset.UTC);

    @Test
    void eighteenthBirthdayTodayIsAdult() {
        assertThat(AgePolicy.isAdult(LocalDate.of(2008, 11, 8), NOON_IST)).isTrue();
    }

    @Test
    void eighteenthBirthdayTomorrowIsNotAdult() {
        assertThat(AgePolicy.isAdult(LocalDate.of(2008, 11, 9), NOON_IST)).isFalse();
    }

    @Test
    void todayIsTakenInIstNotUtc() {
        // 2026-11-07 20:00 UTC is already 2026-11-08 01:30 in IST.
        Clock lateEveningUtc = Clock.fixed(Instant.parse("2026-11-07T20:00:00Z"), ZoneOffset.UTC);

        assertThat(AgePolicy.isAdult(LocalDate.of(2008, 11, 8), lateEveningUtc)).isTrue();
    }

    @Test
    void leapDayBirthdayFollowsPostgresIntervalArithmetic() {
        // PostgreSQL: '2026-02-28' - INTERVAL '18 years' = 2008-02-28, so a
        // 2008-02-29 birthday is not yet 18 on 28 February.
        Clock feb28 = Clock.fixed(Instant.parse("2026-02-28T06:30:00Z"), ZoneOffset.UTC);
        Clock mar01 = Clock.fixed(Instant.parse("2026-03-01T06:30:00Z"), ZoneOffset.UTC);
        LocalDate leapDay = LocalDate.of(2008, 2, 29);

        assertThat(AgePolicy.isAdult(leapDay, feb28)).isFalse();
        assertThat(AgePolicy.isAdult(leapDay, mar01)).isTrue();
    }
}
