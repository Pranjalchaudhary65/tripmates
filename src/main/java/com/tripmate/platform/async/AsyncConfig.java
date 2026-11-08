package com.tripmate.platform.async;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async}. Work runs on Spring Boot's auto-configured
 * application task executor, a bounded pool sized under spring.task.execution.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {
}
