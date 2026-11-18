package com.tripmate.identity.service;

import com.tripmate.platform.error.RetryLaterException;
import com.tripmate.platform.ratelimit.RateLimitProblem;
import com.tripmate.platform.ratelimit.RateLimitProperties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No session store means no answer, never a guess: every operation is refused with 503. */
class RefreshTokenStoreTest {

    private LettuceConnectionFactory unreachable;

    private RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 9));
        unreachable.afterPropertiesSet();
        unreachable.start();
        store = new RefreshTokenStore(new StringRedisTemplate(unreachable),
                new RateLimitProperties(Duration.ofSeconds(1), Duration.ofSeconds(30)));
    }

    @AfterEach
    void tearDown() {
        unreachable.destroy();
    }

    @Test
    void everyOperationFailsClosedWith503() {
        UUID family = UUID.randomUUID();
        List<ThrowingCallable> calls = List.of(
                () -> store.create(family, "hash", Duration.ofDays(30)),
                () -> store.rotate(family, "old", "new", Instant.now(), Duration.ofSeconds(10)),
                () -> store.isLive(family),
                () -> store.delete(List.of(family)));

        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOfSatisfying(RetryLaterException.class, e -> {
                assertThat(e.type()).isEqualTo(RateLimitProblem.TEMPORARILY_UNAVAILABLE);
                assertThat(e.retryAfterSeconds()).isEqualTo(30);
            });
        }
    }
}
