package com.tripmate.platform.logging;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link CorrelationIdFilter} ahead of every other filter,
 * Spring Security's included, so that a request rejected by security still
 * gets an ID in its logs and on its response.
 */
@Configuration(proxyBeanMethods = false)
public class CorrelationIdFilterConfig {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
