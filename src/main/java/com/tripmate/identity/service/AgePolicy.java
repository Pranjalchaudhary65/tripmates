package com.tripmate.identity.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The 18+ rule, computed exactly as the ck_users_adult constraint computes
 * it, so the service and the database always agree on the boundary day.
 *
 * <p>"Today" is IST, not the server's or the session's zone: the platform
 * operates under Indian law, and pinning one zone in both places means a
 * birthday cannot pass here and fail in the database, or the other way
 * round, depending on where the JVM happens to run.
 *
 * <p>The comparison is {@code dob <= today - 18 years}, as in SQL, not
 * {@code dob + 18 years <= today}. They differ for 29 February: Java clamps
 * 2008-02-29 plus 18 years to 2026-02-28, while PostgreSQL's
 * 2026-02-28 minus 18 years is 2008-02-28, before the birthday. Using the
 * same direction as the database keeps the two in step on leap days too.
 */
public final class AgePolicy {

    public static final int MINIMUM_AGE_YEARS = 18;

    public static final ZoneId LEGAL_ZONE = ZoneId.of("Asia/Kolkata");

    private AgePolicy() {
    }

    public static boolean isAdult(LocalDate dateOfBirth, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(LEGAL_ZONE));
        return !dateOfBirth.isAfter(today.minusYears(MINIMUM_AGE_YEARS));
    }
}
