package com.tripmate.vibe.entity;

/**
 * A deal-breaker about a habit (smoking, drinking) on a trip, from the
 * user's side: values match ck_deal_breakers_smoking and _drinking.
 *
 * <ul>
 *   <li>NO: I want a trip without it.</li>
 *   <li>OK: it will be part of my trip, so I need companions who are fine with it.</li>
 *   <li>EITHER: no preference either way.</li>
 * </ul>
 *
 * Matching pairs NO with OK as incompatible and lets EITHER go with both.
 */
public enum HabitPreference {
    NO, OK, EITHER
}
