package com.tripmate.trips.entity;

/**
 * Values match ck_trips_status. OPEN takes join requests and has a slot
 * left; FULL has none left. The later states belong to the trust ladder.
 */
public enum TripStatus {
    DRAFT, OPEN, FULL, LOCKED, COMPLETED, CANCELLED;

    /** Shown to people who are not on the trip: it is taking, or has just stopped taking, companions. */
    public boolean isListed() {
        return this == OPEN || this == FULL;
    }
}
