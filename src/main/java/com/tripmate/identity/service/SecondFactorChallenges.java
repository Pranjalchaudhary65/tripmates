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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Logins that have passed the password and wait for a second factor, one
 * Redis hash each: {@code login-2fa:{sha256(challengeId)} -> {userId,
 * counterKey, attempts, expiresAt}}.
 *
 * <p>The challenge ID is 256 random bits handed to the client; only its
 * hash is a key, so a Redis dump does not hand out live challenges. Expiry is
 * checked against the application clock, like every other deadline in the
 * service; the key's TTL (a little longer) only cleans up.
 *
 * <p>Each code submitted is charged before it is checked, in one Lua script:
 * of any number of parallel guesses, at most {@code maxAttempts} get through,
 * and the one after that finds the challenge deleted.
 *
 * <p>Fails closed like the rest of the login path: Redis unreachable is a 503.
 */
@Component
@EnableConfigurationProperties({TwoFactorProperties.class, RateLimitProperties.class})
public class SecondFactorChallenges {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorChallenges.class);

    private static final String KEY_PREFIX = "login-2fa:";

    /** The key outlives the challenge by this much, so the clock check, not the TTL, decides expiry. */
    private static final Duration CLEANUP_MARGIN = Duration.ofMinutes(1);

    private static final RedisScript<Long> OPEN = RedisScript.of("""
            redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'counterKey', ARGV[2], 'attempts', 0, 'expiresAt', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /**
     * Returns {userId, counterKey} and counts the attempt, or an empty list
     * when there is no such challenge, it has expired, or this attempt is one
     * too many - the last two delete it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final RedisScript<List<String>> CHARGE = (RedisScript) RedisScript.of("""
            local userId = redis.call('HGET', KEYS[1], 'userId')
            if not userId then
              return {}
            end
            if tonumber(ARGV[1]) >= tonumber(redis.call('HGET', KEYS[1], 'expiresAt')) then
              redis.call('DEL', KEYS[1])
              return {}
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts > tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              return {}
            end
            return {userId, redis.call('HGET', KEYS[1], 'counterKey')}
            """, List.class);

    private final StringRedisTemplate redis;

    private final TwoFactorProperties properties;

    private final RateLimitProperties rateLimitProperties;

    public SecondFactorChallenges(StringRedisTemplate redis, TwoFactorProperties properties,
                                  RateLimitProperties rateLimitProperties) {
        this.redis = redis;
        this.properties = properties;
        this.rateLimitProperties = rateLimitProperties;
    }

    /** @return the challenge ID for the client; it is not stored anywhere in this form */
    public SecondFactorChallenge open(UUID userId, String counterKey, Instant now) {
        String challengeId = DeviceIds.generate();
        Instant expiresAt = now.plus(properties.challengeTtl());
        Long result = call(() -> redis.execute(OPEN, List.of(key(challengeId)), userId.toString(), counterKey,
                Long.toString(expiresAt.toEpochMilli()),
                Long.toString(properties.challengeTtl().plus(CLEANUP_MARGIN).toMillis())));
        if (result == null) {
            throw unavailable();
        }
        return new SecondFactorChallenge(challengeId, properties.challengeTtl());
    }

    /** Charges one attempt; empty when the challenge is unknown, expired or used up. */
    public Optional<Pending> charge(String challengeId, Instant now) {
        if (!DeviceIds.isWellFormed(challengeId)) {
            return Optional.empty();
        }
        List<String> result = call(() -> redis.execute(CHARGE, List.of(key(challengeId)),
                Long.toString(now.toEpochMilli()), Integer.toString(properties.challengeMaxAttempts())));
        if (result == null) {
            throw unavailable();
        }
        if (result.size() != 2) {
            return Optional.empty();
        }
        return Optional.of(new Pending(UUID.fromString(result.get(0)), result.get(1)));
    }

    /**
     * Ends the challenge once its code has passed. Only one caller can get
     * {@code true} for a challenge, which is what stops two racing correct
     * submissions from opening two sessions.
     */
    public boolean consume(String challengeId) {
        Boolean deleted = call(() -> redis.delete(key(challengeId)));
        if (deleted == null) {
            throw unavailable();
        }
        return deleted;
    }

    private static String key(String challengeId) {
        return KEY_PREFIX + DeviceIds.sha256Hex(challengeId);
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.warn("Login challenge store unavailable, refusing the request: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private RetryLaterException unavailable() {
        return new RetryLaterException(RateLimitProblem.TEMPORARILY_UNAVAILABLE,
                rateLimitProperties.unavailableRetryAfter());
    }

    /** A charged challenge: whose login it is and which lockout counter its codes go against. */
    public record Pending(UUID userId, String counterKey) {

        @Override
        public String toString() {
            return "Pending[userId=" + userId + "]";
        }
    }
}
