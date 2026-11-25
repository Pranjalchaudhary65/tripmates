package com.tripmate.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param relayInterval how long the relay waits between runs
 * @param maxPerRun     at most this many events are settled in one run, so a
 *                      backlog cannot hold one scheduler thread forever
 * @param maxAttempts   an event whose delivery has failed this many times is
 *                      set aside as dead instead of being retried
 */
@ConfigurationProperties("tripmate.outbox")
public record OutboxProperties(Duration relayInterval, int maxPerRun, int maxAttempts) {

    public OutboxProperties {
        Objects.requireNonNull(relayInterval, "tripmate.outbox.relay-interval");
        if (maxPerRun < 1) {
            throw new IllegalArgumentException("tripmate.outbox.max-per-run must be at least 1");
        }
        if (maxAttempts < 1 || maxAttempts > Short.MAX_VALUE) {
            throw new IllegalArgumentException("tripmate.outbox.max-attempts must be between 1 and 32767");
        }
    }
}
