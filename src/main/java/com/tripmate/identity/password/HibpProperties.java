package com.tripmate.identity.password;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Where the Pwned Passwords range API lives and how long to wait for it. The
 * timeout bounds how much a slow upstream can add to a registration request.
 */
@ConfigurationProperties("tripmate.identity.breached-password")
public record HibpProperties(URI baseUrl, Duration timeout) {

    public HibpProperties {
        Objects.requireNonNull(baseUrl, "tripmate.identity.breached-password.base-url is required");
        Objects.requireNonNull(timeout, "tripmate.identity.breached-password.timeout is required");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("tripmate.identity.breached-password.timeout must be positive");
        }
    }
}
