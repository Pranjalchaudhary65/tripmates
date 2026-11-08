package com.tripmate.platform.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Services read the time from this bean instead of calling Instant.now(), so
 * date rules such as the age check can be tested on a fixed day.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
