package com.tripmate.safety.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * @param writeLimit    sharing changes and check-ins, per account
 * @param readLimit     the live trip screen, per account
 * @param sosLimit      SOS presses per account: generous, since it is the one action that must never be refused
 *                      in earnest, but bounded so nobody can flood someone's emergency contact
 * @param escalateAfter a check-in still unanswered this long after it was due alerts the emergency contact
 * @param maxSosAlertAttempts tries at delivering one SOS alert before the job leaves it to a moderator
 * @param jobInterval   how often the safety job runs
 * @param jobBatch      check-ins or SOS events one run handles at most
 */
@ConfigurationProperties("tripmate.safety")
public record SafetyProperties(RateLimit writeLimit, RateLimit readLimit, RateLimit sosLimit, Duration escalateAfter,
                               int maxSosAlertAttempts, Duration jobInterval, int jobBatch) {

    public SafetyProperties {
        String prefix = "tripmate.safety.";
        Objects.requireNonNull(writeLimit, prefix + "write-limit is required");
        Objects.requireNonNull(readLimit, prefix + "read-limit is required");
        Objects.requireNonNull(sosLimit, prefix + "sos-limit is required");
        Objects.requireNonNull(escalateAfter, prefix + "escalate-after is required");
        Objects.requireNonNull(jobInterval, prefix + "job-interval is required");
        if (!escalateAfter.isPositive() || maxSosAlertAttempts < 1 || jobBatch < 1) {
            throw new IllegalArgumentException(prefix + "escalate-after, max-sos-alert-attempts or job-batch out of range");
        }
    }
}
