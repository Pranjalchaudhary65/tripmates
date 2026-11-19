package com.tripmate.identity.service;

import java.time.Duration;
import java.util.Objects;

/**
 * A login whose password was right and which now waits for a TOTP or backup
 * code. The ID is a bearer secret for the rest of the login: it stays out of
 * logs.
 */
public record SecondFactorChallenge(String id, Duration expiresIn) {

    public SecondFactorChallenge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(expiresIn, "expiresIn");
    }

    @Override
    public String toString() {
        return "SecondFactorChallenge[expiresIn=" + expiresIn + "]";
    }
}
