package com.tripmate.platform.error;

import java.time.Duration;
import java.util.Objects;

/**
 * A {@link ProblemType} answer that also tells the client when to come back:
 * the handler adds a {@code Retry-After} header (in whole seconds, rounded
 * up) to the usual ProblemDetail body.
 */
public class RetryLaterException extends ApiProblemException {

    private final Duration retryAfter;

    public RetryLaterException(ProblemType type, Duration retryAfter) {
        super(type);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    /** At least 1: "Retry-After: 0" would invite an immediate retry into the same refusal. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
