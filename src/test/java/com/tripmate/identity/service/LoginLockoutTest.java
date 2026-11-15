package com.tripmate.identity.service;

import com.tripmate.platform.error.RetryLaterException;
import com.tripmate.platform.ratelimit.RateLimitProblem;
import com.tripmate.platform.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Without Redis there is no failure count to check, so login is refused rather than let through uncounted. */
class LoginLockoutTest {

    private LettuceConnectionFactory unreachable;

    private LoginLockout lockout;

    @BeforeEach
    void setUp() {
        unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 9));
        unreachable.afterPropertiesSet();
        unreachable.start();
        lockout = new LoginLockout(new StringRedisTemplate(unreachable),
                new LoginProperties(3, new LoginProperties.Lockout(5, Duration.ofMinutes(1),
                        Duration.ofMinutes(15), Duration.ofHours(24))),
                new RateLimitProperties(Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    @AfterEach
    void tearDown() {
        unreachable.destroy();
    }

    @Test
    void anUnreachableStoreFailsClosedWith503() {
        assertThatThrownBy(() -> lockout.charge("some-key", Instant.now()))
                .isInstanceOfSatisfying(RetryLaterException.class,
                        e -> assertThat(e.type()).isEqualTo(RateLimitProblem.TEMPORARILY_UNAVAILABLE));
        assertThatThrownBy(() -> lockout.failures("some-key")).isInstanceOf(RetryLaterException.class);
    }
}
