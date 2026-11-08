package com.tripmate.identity.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * @param from the From header on every identity email, e.g. {@code TripMates <no-reply@example.com>}
 */
@ConfigurationProperties("tripmate.mail")
public record MailProperties(String from) {

    public MailProperties {
        Objects.requireNonNull(from, "tripmate.mail.from is required");
    }
}
