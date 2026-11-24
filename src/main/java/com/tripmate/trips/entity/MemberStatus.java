package com.tripmate.trips.entity;

/**
 * Values match ck_trip_members_status. A join request starts as REQUESTED
 * and the host turns it into ACCEPTED or REJECTED; the later states belong
 * to the trust ladder.
 */
public enum MemberStatus {
    REQUESTED, ACCEPTED, REJECTED, LOCKED, CANCELLED, COMPLETED
}
