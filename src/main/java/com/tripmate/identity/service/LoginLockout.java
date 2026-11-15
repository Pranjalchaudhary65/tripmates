package com.tripmate.identity.service;

import com.tripmate.platform.error.RetryLaterException;
import com.tripmate.platform.ratelimit.RateLimitProblem;
import com.tripmate.platform.ratelimit.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Consecutive-failure counts and lock deadlines, one Redis hash per lockout
 * key: {@code failures} and {@code lockedUntil} (epoch millis).
 *
 * <p>An attempt is charged <em>before</em> its password is checked, in one
 * Lua script that Redis runs atomically. Checking first and counting after
 * would let N parallel guesses all read "not locked yet" and all reach the
 * password check; charging up front means at most {@code afterFailures}
 * attempts are ever let through before the lock, however many arrive at once.
 * A success then clears the count it was charged to.
 *
 * <p>Attempts refused because the key is locked are not counted: otherwise
 * anyone could hold a victim's lock at the maximum by sending one request a
 * minute. Time comes from the application clock, passed in, so the
 * deadlines follow the same clock as everything else in the service.
 *
 * <p>Like the rate limiter, this fails closed: Redis unreachable means 503.
 */
@Component
@EnableConfigurationProperties({LoginProperties.class, RateLimitProperties.class})
public class LoginLockout {

    private static final Logger log = LoggerFactory.getLogger(LoginLockout.class);

    private static final String KEY_PREFIX = "login-lockout:";

    private static final String FAILURES = "failures";

    /**
     * Returns the new failure count when the attempt may proceed, or minus the
     * lock deadline when it may not. The exponent is capped so the double
     * never overflows; max-duration caps the lock long before that anyway.
     */
    private static final RedisScript<Long> CHARGE_ATTEMPT = RedisScript.of("""
            local now = tonumber(ARGV[1])
            local lockedUntil = tonumber(redis.call('HGET', KEYS[1], 'lockedUntil') or '0')
            if lockedUntil > now then
              return -lockedUntil
            end
            local failures = redis.call('HINCRBY', KEYS[1], 'failures', 1)
            local threshold = tonumber(ARGV[2])
            if failures >= threshold then
              local lockMillis = math.min(tonumber(ARGV[3]) * 2 ^ math.min(failures - threshold, 30), tonumber(ARGV[4]))
              redis.call('HSET', KEYS[1], 'lockedUntil', string.format('%.0f', now + lockMillis))
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return failures
            """, Long.class);

    private final StringRedisTemplate redis;

    private final LoginProperties.Lockout policy;

    private final RateLimitProperties rateLimitProperties;

    public LoginLockout(StringRedisTemplate redis, LoginProperties properties, RateLimitProperties rateLimitProperties) {
        this.redis = redis;
        this.policy = properties.lockout();
        this.rateLimitProperties = rateLimitProperties;
    }

    /** Failures charged to {@code key} since its last success, including attempts still in flight. */
    public int failures(String key) {
        Object value = call(() -> redis.opsForHash().get(KEY_PREFIX + key, FAILURES));
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

    /** Charges one attempt to {@code key}, or refuses it if the key is locked at {@code now}. */
    public Attempt charge(String key, Instant now) {
        Long result = call(() -> redis.execute(CHARGE_ATTEMPT, List.of(KEY_PREFIX + key),
                Long.toString(now.toEpochMilli()),
                Integer.toString(policy.afterFailures()),
                Long.toString(policy.baseDuration().toMillis()),
                Long.toString(policy.maxDuration().toMillis()),
                Long.toString(policy.failureWindow().toMillis())));
        if (result == null) {
            throw unavailable();
        }
        return result < 0 ? Attempt.lockedUntil(Instant.ofEpochMilli(-result)) : Attempt.allowed();
    }

    public void reset(String key) {
        call(() -> redis.delete(KEY_PREFIX + key));
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.warn("Lockout store unavailable, refusing the login: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private RetryLaterException unavailable() {
        return new RetryLaterException(RateLimitProblem.TEMPORARILY_UNAVAILABLE,
                rateLimitProperties.unavailableRetryAfter());
    }

    /** @param lockedUntil null when the attempt may proceed */
    public record Attempt(Instant lockedUntil) {

        static Attempt allowed() {
            return new Attempt(null);
        }

        static Attempt lockedUntil(Instant until) {
            return new Attempt(until);
        }

        public boolean isAllowed() {
            return lockedUntil == null;
        }
    }
}
