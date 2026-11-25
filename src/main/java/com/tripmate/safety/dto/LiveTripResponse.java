package com.tripmate.safety.dto;

import com.tripmate.safety.service.LiveTripViews.Phase;
import com.tripmate.safety.service.LiveTripViews.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The live trip screen. Nobody's location is in it: other travellers appear
 * only as a first name, whether they share the trip, and when they last said
 * they were safe.
 *
 * @param sharing     null while the caller is not sharing the trip with their emergency contact
 * @param nextCheckIn null when nothing is waiting
 * @param lastCheckIn null before the caller's first answered check-in
 * @param openSos     the caller's SOS on this trip that is not resolved, if any
 */
public record LiveTripResponse(Trip trip, Phase phase, boolean locked, boolean emergencyContactSet, Sharing sharing,
                               NextCheckIn nextCheckIn, LastCheckIn lastCheckIn, List<Buddy> buddies,
                               OpenSos openSos) {

    public record Trip(UUID id, String title, String destinationName, double destinationLatitude,
                       double destinationLongitude, LocalDate startDate, LocalDate endDate) {
    }

    public record Sharing(int checkInEveryHours, boolean noteSet, Instant startedAt) {
    }

    public record NextCheckIn(Instant dueAt, boolean overdue) {
    }

    /** @param kmFromDestination null when no location was shared */
    public record LastCheckIn(Instant at, Double kmFromDestination) {
    }

    public record Buddy(String firstName, Role role, boolean sharing, Instant lastCheckInAt) {
    }

    /** @param contactAlerted the emergency contact alert has been handed over */
    public record OpenSos(UUID id, Instant triggeredAt, boolean contactAlerted) {
    }
}
