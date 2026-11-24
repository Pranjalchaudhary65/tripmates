package com.tripmate.meetup.entity;

/**
 * Values match ck_meetups_status. PROPOSED by one person, CONFIRMED by the
 * other, then COMPLETED when both have checked in at the venue, NO_SHOW when
 * the check-in window closed without that, or CANCELLED by either at any
 * point before it completed.
 */
public enum MeetupStatus {
    PROPOSED, CONFIRMED, COMPLETED, CANCELLED, NO_SHOW;

    /** Being arranged: at most one per match at a time (uq_meetups_active). */
    public boolean isActive() {
        return this == PROPOSED || this == CONFIRMED;
    }
}
