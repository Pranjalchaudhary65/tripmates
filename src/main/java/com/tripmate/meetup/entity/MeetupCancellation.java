package com.tripmate.meetup.entity;

/**
 * Why a meet-up was called off. SAFETY ends the match for both people,
 * silently and at no cost to the person who chose it. CONVENIENCE is a change
 * of plan: another meet-up can be proposed.
 */
public enum MeetupCancellation {
    SAFETY, CONVENIENCE
}
