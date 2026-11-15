package com.tripmate.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param captchaAfterFailures consecutive failures on an account before a CAPTCHA is required
 * @param lockout              when and for how long repeated failures lock an account
 */
@ConfigurationProperties("tripmate.identity.login")
public record LoginProperties(int captchaAfterFailures, Lockout lockout) {

    public LoginProperties {
        if (captchaAfterFailures < 1) {
            throw new IllegalArgumentException("tripmate.identity.login.captcha-after-failures must be positive");
        }
        Objects.requireNonNull(lockout, "tripmate.identity.login.lockout is required");
    }

    /**
     * The attempt that brings the count to {@code afterFailures} locks the
     * account for {@code baseDuration}; each further failure doubles the
     * lock, up to {@code maxDuration}. The count is dropped
     * {@code failureWindow} after the last failure, or on a success.
     */
    public record Lockout(int afterFailures, Duration baseDuration, Duration maxDuration, Duration failureWindow) {

        public Lockout {
            if (afterFailures < 1) {
                throw new IllegalArgumentException("tripmate.identity.login.lockout.after-failures must be positive");
            }
            Objects.requireNonNull(baseDuration, "tripmate.identity.login.lockout.base-duration is required");
            Objects.requireNonNull(maxDuration, "tripmate.identity.login.lockout.max-duration is required");
            Objects.requireNonNull(failureWindow, "tripmate.identity.login.lockout.failure-window is required");
            if (baseDuration.isNegative() || baseDuration.isZero() || maxDuration.compareTo(baseDuration) < 0) {
                throw new IllegalArgumentException("lockout durations must satisfy 0 < base-duration <= max-duration");
            }
            if (failureWindow.compareTo(maxDuration) < 0) {
                throw new IllegalArgumentException("lockout failure-window must be at least max-duration");
            }
        }
    }
}
