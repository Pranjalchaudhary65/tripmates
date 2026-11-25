package com.tripmate.safety.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the live trip service hands the controllers. Nothing here carries
 * anyone's location, or the emergency contact: other travellers appear as a
 * first name and when they last said they were safe.
 */
public final class LiveTripViews {

    private LiveTripViews() {
    }

    public enum Phase {
        /** The trip has not started; sharing can be switched on ahead of it. */
        BEFORE,
        ACTIVE,
        /** Over: no more check-ins are scheduled. */
        ENDED
    }

    public enum Role {
        HOST, COMPANION
    }

    /**
     * The live trip screen in one call.
     *
     * @param sharing     null while the caller is not sharing the trip
     * @param nextCheckIn null when nothing is waiting
     * @param lastCheckIn null before the caller's first answered check-in
     * @param openSos     the caller's SOS still open on this trip, if any
     */
    public record LiveTripView(TripRef trip, Phase phase, boolean locked, boolean emergencyContactSet,
                               Sharing sharing, NextCheckIn nextCheckIn, LastCheckIn lastCheckIn,
                               List<Buddy> buddies, OpenSos openSos) {
    }

    public record TripRef(UUID id, String title, String destinationName, double destinationLatitude,
                          double destinationLongitude, LocalDate startDate, LocalDate endDate) {
    }

    public record Sharing(int checkInEveryHours, boolean noteSet, Instant startedAt) {
    }

    public record NextCheckIn(Instant dueAt, boolean overdue) {
    }

    /** @param kmFromDestination from the rounded point, to one decimal; null when no location was shared */
    public record LastCheckIn(Instant at, Double kmFromDestination) {
    }

    /** @param lastCheckInAt when they last said they were safe; null if never */
    public record Buddy(String firstName, Role role, boolean sharing, Instant lastCheckInAt) {
    }

    /** @param contactAlerted the emergency contact alert has been handed over */
    public record OpenSos(UUID id, Instant triggeredAt, boolean contactAlerted) {
    }

    /** A point the phone reported, checked for range; accuracy may be missing. */
    public record ReportedLocation(double latitude, double longitude, Double accuracyMeters) {
    }
}
