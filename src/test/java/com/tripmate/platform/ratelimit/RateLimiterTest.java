package com.tripmate.platform.ratelimit;

import com.tripmate.platform.error.RetryLaterException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What happens to a limited request when Redis is not there: it is refused, never waved through. */
class RateLimiterTest {

    /** The discard port; nothing listens on it, so every connect is refused at once. */
    private final RedisClient unreachable = RedisClient.create(RedisURI.builder()
            .withHost("127.0.0.1").withPort(9).withTimeout(Duration.ofSeconds(1)).build());

    private final RateLimiter limiter = new RateLimiter(
            () -> unreachable.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)),
            new RateLimitProperties(Duration.ofSeconds(1), Duration.ofSeconds(30)));

    @AfterEach
    void shutdown() {
        unreachable.shutdown();
    }

    @Test
    void anUnreachableStoreFailsClosedWith503AndRetryAfter() {
        // The second call lands inside the reconnect hold-off and must be refused just the same.
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> limiter.consume("test", "203.0.113.5", new RateLimit(5, Duration.ofMinutes(1))))
                    .isInstanceOfSatisfying(RetryLaterException.class, e -> {
                        assertThat(e.type()).isEqualTo(RateLimitProblem.TEMPORARILY_UNAVAILABLE);
                        assertThat(e.retryAfterSeconds()).isEqualTo(30);
                    });
        }
    }
}
