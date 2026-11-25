package com.tripmate.moderation.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param writeLimit          blocks, unblocks and reports, per account
 * @param readLimit           the block list, per account
 * @param autoSuspendReporters this many different people reporting one account within
 *                            {@code autoSuspendWindow} suspends it until a moderator looks
 * @param autoSuspendWindow   the rolling window those reports are counted in
 */
@ConfigurationProperties("tripmate.moderation")
public record ModerationProperties(RateLimit writeLimit, RateLimit readLimit, int autoSuspendReporters,
                                   Duration autoSuspendWindow) {

    public ModerationProperties {
        String prefix = "tripmate.moderation.";
        Objects.requireNonNull(writeLimit, prefix + "write-limit is required");
        Objects.requireNonNull(readLimit, prefix + "read-limit is required");
        Objects.requireNonNull(autoSuspendWindow, prefix + "auto-suspend-window is required");
        if (autoSuspendReporters < 2 || !autoSuspendWindow.isPositive()) {
            throw new IllegalArgumentException(prefix + "auto-suspend-reporters must be at least 2, so one person "
                    + "can never suspend another alone");
        }
    }
}
