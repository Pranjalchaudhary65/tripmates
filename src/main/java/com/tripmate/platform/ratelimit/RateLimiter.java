package com.tripmate.platform.ratelimit;

import com.tripmate.platform.error.RetryLaterException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Token buckets kept in Redis, so every app instance counts against the same
 * bucket and a restart does not hand everyone a fresh allowance. Bucket4j
 * updates a bucket with compare-and-swap, so concurrent requests can never
 * take more tokens than the bucket holds.
 *
 * <p><b>Fails closed.</b> If Redis cannot be reached, or does not answer
 * within {@code tripmate.rate-limit.store-timeout}, the request is refused
 * with 503 and a Retry-After. These limits are the main defence against
 * password guessing; letting requests through unchecked whenever Redis is
 * down would switch that defence off for anyone able to cause the outage.
 *
 * <p>The Redis connection is opened on first use rather than at startup, so
 * Redis being down at boot gives 503s until it is back instead of an app that
 * will not start. After a failed connect, further attempts wait a few seconds
 * so a Redis outage does not turn every request into a connect timeout.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "rate-limit:";

    private static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    /** A full bucket carries no information, so its key is dropped shortly after it refills. */
    private static final Duration KEEP_FULL_BUCKET_FOR = Duration.ofMinutes(1);

    private static final long RECONNECT_HOLD_OFF_NANOS = Duration.ofSeconds(5).toNanos();

    private final Supplier<StatefulRedisConnection<String, byte[]>> connector;

    private final RateLimitProperties properties;

    private StatefulRedisConnection<String, byte[]> connection;

    private volatile ProxyManager<String> buckets;

    private volatile long nextConnectAttemptNanos;

    @Autowired
    public RateLimiter(LettuceConnectionFactory connectionFactory, RateLimitProperties properties) {
        this(() -> standaloneClient(connectionFactory).connect(CODEC), properties);
    }

    RateLimiter(Supplier<StatefulRedisConnection<String, byte[]>> connector, RateLimitProperties properties) {
        this.connector = connector;
        this.properties = properties;
    }

    /**
     * Takes one token from the bucket {@code bucket:key}, creating it full
     * under {@code limit} if it does not exist yet.
     *
     * @throws RetryLaterException 429 with the wait until the next token when
     *         the bucket is empty; 503 when the bucket could not be checked
     */
    public void consume(String bucket, String key, RateLimit limit) {
        ConsumptionProbe probe;
        try {
            probe = buckets().builder()
                    .build(KEY_PREFIX + bucket + ":" + key, () -> configuration(limit))
                    .tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.warn("Rate limit store unavailable, refusing the request: {}", e.getClass().getSimpleName());
            throw new RetryLaterException(RateLimitProblem.TEMPORARILY_UNAVAILABLE, properties.unavailableRetryAfter());
        }
        if (!probe.isConsumed()) {
            throw new RetryLaterException(RateLimitProblem.RATE_LIMITED,
                    Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

    private ProxyManager<String> buckets() {
        ProxyManager<String> current = buckets;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (buckets == null) {
                if (System.nanoTime() - nextConnectAttemptNanos < 0) {
                    throw new IllegalStateException("Redis connect attempt is on hold after a recent failure");
                }
                try {
                    connection = connector.get();
                } catch (RuntimeException e) {
                    nextConnectAttemptNanos = System.nanoTime() + RECONNECT_HOLD_OFF_NANOS;
                    throw e;
                }
                buckets = LettuceBasedProxyManager.builderFor(connection)
                        .withExpirationStrategy(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_FULL_BUCKET_FOR))
                        .withClientSideConfig(ClientSideConfig.getDefault()
                                .withRequestTimeout(properties.storeTimeout()))
                        .build();
            }
            return buckets;
        }
    }

    private static BucketConfiguration configuration(RateLimit limit) {
        return BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build();
    }

    /**
     * The Lettuce client Spring Boot already configured from spring.data.redis.*,
     * so host, credentials, TLS and timeouts are set in one place only.
     */
    private static RedisClient standaloneClient(LettuceConnectionFactory connectionFactory) {
        AbstractRedisClient client = connectionFactory.getRequiredNativeClient();
        if (client instanceof RedisClient standalone) {
            return standalone;
        }
        throw new IllegalStateException("Rate limiting supports a standalone Redis only, not "
                + client.getClass().getSimpleName());
    }

    @Override
    public synchronized void destroy() {
        if (connection != null) {
            connection.close();
        }
    }
}
