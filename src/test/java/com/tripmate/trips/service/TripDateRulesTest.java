package com.tripmate.trips.service;

import com.tripmate.platform.error.ApiProblemException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripDateRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Test
    void startingTodayAndLastingTheLongestAllowedIsFine() {
        assertThatCode(() -> TripService.requireValidDates(TODAY, TODAY.plusDays(TripService.MAX_LENGTH_DAYS), TODAY))
                .doesNotThrowAnyException();
        assertThatCode(() -> TripService.requireValidDates(TODAY.plusDays(TripService.MAX_DAYS_AHEAD),
                TODAY.plusDays(TripService.MAX_DAYS_AHEAD), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void thePastTooFarAheadBackwardsOrTooLongIsRefused() {
        assertRefused(TODAY.minusDays(1), TODAY);
        assertRefused(TODAY.plusDays(TripService.MAX_DAYS_AHEAD + 1), TODAY.plusDays(TripService.MAX_DAYS_AHEAD + 2));
        assertRefused(TODAY.plusDays(3), TODAY.plusDays(2));
        assertRefused(TODAY, TODAY.plusDays(TripService.MAX_LENGTH_DAYS + 1));
    }

    @Test
    void todayIsTheIndianDateEvenWhenUtcIsStillOnTheDayBefore() {
        Clock lateUtcEvening = Clock.fixed(Instant.parse("2026-11-23T20:00:00Z"), ZoneOffset.UTC);

        assertThat(TripCalendar.today(lateUtcEvening)).isEqualTo(TODAY);
    }

    private static void assertRefused(LocalDate start, LocalDate end) {
        assertThatThrownBy(() -> TripService.requireValidDates(start, end, TODAY))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.type()).isEqualTo(TripProblem.INVALID_TRIP_DATES));
    }
}
