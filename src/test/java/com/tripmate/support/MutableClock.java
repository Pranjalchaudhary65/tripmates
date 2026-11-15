package com.tripmate.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The system clock plus an offset a test can move forward, so a rule that
 * waits minutes (a lockout ending, a code expiring) is tested without
 * sleeping. Shared by every integration test, so a test that advances it
 * must {@link #reset()} it afterwards.
 */
public final class MutableClock extends Clock {

    private final Clock base = Clock.systemUTC();

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration by) {
        offset = offset.plus(by);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(base.withZone(zone), offset);
    }
}
