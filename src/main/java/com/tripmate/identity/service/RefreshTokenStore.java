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
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The rotating half of a refresh token family, one Redis hash per family:
 * {@code refresh:{familyId} -> {current, previous, rotatedAt}}, expiring with
 * the family. Values are SHA-256 hashes, never tokens.
 *
 * <p>Rotation is one Lua script, which Redis runs atomically: of any number
 * of concurrent refreshes presenting the current token, exactly one sees it
 * as current and rotates it. Checking in Java and then writing would let two
 * of them both read "current" and both mint a new token, leaving two live
 * tokens in one family.
 *
 * <p>Like the login limits, this fails closed: Redis unreachable means 503,
 * never "assume the token is fine".
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final String KEY_PREFIX = "refresh:";

    private static final RedisScript<Long> CREATE = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'current', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /**
     * Returns 1 rotated, 2 previous token inside the grace window, 3 reuse
     * (the key is deleted before returning, so every later call sees 0),
     * 0 no such family. HSET leaves the key's expiry untouched, so rotating
     * never extends a family's life.
     */
    private static final RedisScript<Long> ROTATE = RedisScript.of("""
            local current = redis.call('HGET', KEYS[1], 'current')
            if not current then
              return 0
            end
            if current == ARGV[1] then
              redis.call('HSET', KEYS[1], 'current', ARGV[2], 'previous', ARGV[1], 'rotatedAt', ARGV[3])
              return 1
            end
            if redis.call('HGET', KEYS[1], 'previous') == ARGV[1] then
              local age = tonumber(ARGV[3]) - tonumber(redis.call('HGET', KEYS[1], 'rotatedAt'))
              if age >= 0 and age <= tonumber(ARGV[4]) then
                return 2
              end
            end
            redis.call('DEL', KEYS[1])
            return 3
            """, Long.class);

    private final StringRedisTemplate redis;

    private final RateLimitProperties rateLimitProperties;

    public RefreshTokenStore(StringRedisTemplate redis, RateLimitProperties rateLimitProperties) {
        this.redis = redis;
        this.rateLimitProperties = rateLimitProperties;
    }

    /** Starts a family whose current token has {@code tokenHash}, expiring after {@code ttl}. */
    public void create(UUID familyId, String tokenHash, Duration ttl) {
        Long result = call(() -> redis.execute(CREATE, List.of(key(familyId)), tokenHash,
                Long.toString(ttl.toMillis())));
        if (result == null) {
            throw unavailable();
        }
    }

    public Rotation rotate(UUID familyId, String presentedHash, String nextHash, Instant now, Duration grace) {
        Long result = call(() -> redis.execute(ROTATE, List.of(key(familyId)), presentedHash, nextHash,
                Long.toString(now.toEpochMilli()), Long.toString(grace.toMillis())));
        if (result == null) {
            throw unavailable();
        }
        return switch (result.intValue()) {
            case 1 -> Rotation.ROTATED;
            case 2 -> Rotation.RACED_WITHIN_GRACE;
            case 3 -> Rotation.REUSED;
            default -> Rotation.UNKNOWN_FAMILY;
        };
    }

    /** Asked on every authenticated request: does the family behind this access token still exist? */
    public boolean isLive(UUID familyId) {
        Boolean exists = call(() -> redis.hasKey(key(familyId)));
        if (exists == null) {
            throw unavailable();
        }
        return exists;
    }

    public void delete(Collection<UUID> familyIds) {
        if (familyIds.isEmpty()) {
            return;
        }
        List<String> keys = familyIds.stream().map(RefreshTokenStore::key).toList();
        call(() -> redis.delete(keys));
    }

    private static String key(UUID familyId) {
        return KEY_PREFIX + familyId;
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.warn("Session store unavailable, refusing the request: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private RetryLaterException unavailable() {
        return new RetryLaterException(RateLimitProblem.TEMPORARILY_UNAVAILABLE,
                rateLimitProperties.unavailableRetryAfter());
    }

    public enum Rotation {

        /** The presented token was current and has been replaced. */
        ROTATED,

        /**
         * The presented token was replaced moments ago: a second tab or a
         * retried request racing the one that rotated it.
         */
        RACED_WITHIN_GRACE,

        /** An older token, or the previous one too late: treated as theft. The family is gone. */
        REUSED,

        /** No such family in Redis: expired, revoked, or never existed. */
        UNKNOWN_FAMILY
    }
}
