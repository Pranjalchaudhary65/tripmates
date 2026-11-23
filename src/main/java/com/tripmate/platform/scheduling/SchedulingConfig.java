package com.tripmate.platform.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}. Jobs run on Spring Boot's auto-configured
 * scheduler. Every job must be safe to run on several app instances at once,
 * since nothing here elects a single runner.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
