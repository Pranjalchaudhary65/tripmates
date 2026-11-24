package com.tripmate.trips.entity;

import com.tripmate.vibe.entity.BudgetBand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripTest {

    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");

    private static final LocalDate START = LocalDate.of(2026, 12, 1);

    @Test
    void claimingSlotsFillsTheTripAndTheLastOneMarksItFull() {
        Trip trip = Trip.open(UUID.randomUUID(), details(2), NOW);

        trip.claimSlot(NOW);
        assertThat(trip.getFilledSlots()).isEqualTo((short) 1);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.OPEN);

        trip.claimSlot(NOW);
        assertThat(trip.getFilledSlots()).isEqualTo((short) 2);
        assertThat(trip.getStatus()).isEqualTo(TripStatus.FULL);
    }

    @Test
    void aFullTripRefusesAnotherClaimWhateverTheCallerChecked() {
        Trip trip = Trip.open(UUID.randomUUID(), details(1), NOW);
        trip.claimSlot(NOW);

        assertThatThrownBy(() -> trip.claimSlot(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(trip.getFilledSlots()).isEqualTo((short) 1);
    }

    @Test
    void aTripHasStartedOnlyAfterItsStartDate() {
        Trip trip = Trip.open(UUID.randomUUID(), details(1), NOW);

        assertThat(trip.hasStarted(START.minusDays(1))).isFalse();
        assertThat(trip.hasStarted(START)).isFalse();
        assertThat(trip.hasStarted(START.plusDays(1))).isTrue();
    }

    @Test
    void detailsOutsideTheTableRulesAreRefusedBeforeTheDatabaseSeesThem() {
        assertThatThrownBy(() -> details(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> details(Trip.MAX_SLOTS + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TripDetails("Title", "Description", "Place", 91, 0, BigDecimal.ZERO, START,
                START, 1, BudgetBand.MID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TripDetails("Title", "Description", "Place", 0, 0, BigDecimal.ZERO, START,
                START.minusDays(1), 1, BudgetBand.MID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRequestIsDecidedOnceAndOnlyOnce() {
        TripMember accepted = TripMember.request(UUID.randomUUID(), UUID.randomUUID(), NOW);
        accepted.accept(NOW);
        assertThat(accepted.getStatus()).isEqualTo(MemberStatus.ACCEPTED);
        assertThat(accepted.getDecidedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> accepted.reject(NOW)).isInstanceOf(IllegalStateException.class);

        TripMember rejected = TripMember.request(UUID.randomUUID(), UUID.randomUUID(), NOW);
        rejected.reject(NOW);
        assertThat(rejected.getStatus()).isEqualTo(MemberStatus.REJECTED);
        assertThatThrownBy(() -> rejected.accept(NOW)).isInstanceOf(IllegalStateException.class);
    }

    private static TripDetails details(int slots) {
        return new TripDetails("Hills and chai", "Slow days.", "Somewhere quiet", 12.9, 77.6, BigDecimal.TEN,
                START, START.plusDays(3), slots, BudgetBand.MID);
    }
}
