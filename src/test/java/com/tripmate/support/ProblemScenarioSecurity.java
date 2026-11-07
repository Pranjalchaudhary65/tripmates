package com.tripmate.support;

import com.tripmate.platform.security.ApiCorsFilter;
import com.tripmate.platform.security.CorsProperties;
import com.tripmate.platform.security.SecurityHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Opens only {@link ProblemScenarioController}'s paths to anonymous callers,
 * so error handling can be tested before any login exists. It is a separate
 * chain matched ahead of the application's own; every other path is still
 * governed by the real SecurityConfig, and the /error dispatch in particular
 * is not matched here. It carries the same response headers and CORS filter
 * as the real chain, so these paths behave like any future /api/v1 endpoint.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProblemScenarioSecurity {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain problemScenarioFilterChain(
            HttpSecurity http,
            CorsProperties corsProperties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        return http
                .securityMatcher(ProblemScenarioController.BASE + "/**")
                .headers(SecurityHeaders.policy())
                .cors(AbstractHttpConfigurer::disable)
                .addFilterAfter(new ApiCorsFilter(corsProperties, exceptionResolver), HeaderWriterFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
