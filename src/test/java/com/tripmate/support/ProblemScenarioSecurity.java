package com.tripmate.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens only {@link ProblemScenarioController}'s paths to anonymous callers,
 * so error handling can be tested before any login exists. It is a separate
 * chain matched ahead of the application's own; every other path is still
 * governed by the real SecurityConfig, and the /error dispatch in particular
 * is not matched here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProblemScenarioSecurity {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain problemScenarioFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(ProblemScenarioController.BASE + "/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
