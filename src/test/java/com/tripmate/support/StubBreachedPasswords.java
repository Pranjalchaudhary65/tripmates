package com.tripmate.support;

import com.tripmate.identity.password.BreachedPasswordChecker;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the Have I Been Pwned client in every integration test, so the
 * suite never calls the network and can force each outcome on demand.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubBreachedPasswords {

    public static final String BREACHED_PASSWORD = "known-breached-password-1";

    public static final String UNCHECKABLE_PASSWORD = "breach-corpus-is-down-1";

    @Bean
    @Primary
    BreachedPasswordChecker stubBreachedPasswordChecker() {
        return password -> switch (password) {
            case BREACHED_PASSWORD -> BreachedPasswordChecker.BreachStatus.BREACHED;
            case UNCHECKABLE_PASSWORD -> BreachedPasswordChecker.BreachStatus.UNAVAILABLE;
            default -> BreachedPasswordChecker.BreachStatus.NOT_FOUND;
        };
    }
}
