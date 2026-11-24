package com.tripmate.trips.entity;

/**
 * Values match ck_trip_members_cancellation_type. SAFETY never costs the
 * person who chose it anything (DESIGN.md, Trust Score Engine, safety
 * carve-out); CONVENIENCE is an ordinary change of plan.
 */
public enum CancellationType {
    CONVENIENCE, SAFETY
}
