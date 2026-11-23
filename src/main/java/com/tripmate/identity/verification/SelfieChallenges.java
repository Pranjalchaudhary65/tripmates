package com.tripmate.identity.verification;

import com.tripmate.identity.service.DeviceIds;
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
 * Started selfie checks, one Redis hash each:
 * {@code selfie-check:{sha256(challengeId)} -> {userId, prompt, expiresAt}}.
 *
 * <p>A check is good for one submission, by the account that started it,
 * within its time limit: taking it reads and deletes it in one Lua script,
 * so two submissions racing with the same ID get one success between them.
 * The short limit is part of the liveness signal: frames have to be captured
 * after the prompt was chosen.
 *
 * <p>Fails closed: Redis unreachable is a 503, never a check skipped.
 */
@Component
@EnableConfigurationProperties({SelfieProperties.class, RateLimitProperties.class})
public class SelfieChallenges {

    private static final Logger log = LoggerFactory.getLogger(SelfieChallenges.class);

    private static final String KEY_PREFIX = "selfie-check:";

    private static final Duration CLEANUP_MARGIN = Duration.ofMinutes(1);

    private static final RedisScript<Long> OPEN = RedisScript.of("""
            redis.call('HSET', KEYS[1], 'userId', ARGV[1], 'prompt', ARGV[2], 'expiresAt', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /** Returns {userId, prompt, expiresAt} and deletes the check, or an empty list when there is none. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final RedisScript<List<String>> TAKE = (RedisScript) RedisScript.of("""
            local fields = redis.call('HMGET', KEYS[1], 'userId', 'prompt', 'expiresAt')
            if not fields[1] then
              return {}
            end
            redis.call('DEL', KEYS[1])
            return fields
            """, List.class);

    private final StringRedisTemplate redis;

    private final SelfieProperties properties;

    private final RateLimitProperties rateLimitProperties;

    public SelfieChallenges(StringRedisTemplate redis, SelfieProperties properties,
                            RateLimitProperties rateLimitProperties) {
        this.redis = redis;
        this.properties = properties;
        this.rateLimitProperties = rateLimitProperties;
    }

    public Challenge open(UUID userId, LivenessPrompt prompt, Instant now) {
        String challengeId = DeviceIds.generate();
        Instant expiresAt = now.plus(properties.challengeTtl());
        Long result = call(() -> redis.execute(OPEN, List.of(key(challengeId)), userId.toString(), prompt.name(),
                Long.toString(expiresAt.toEpochMilli()),
                Long.toString(properties.challengeTtl().plus(CLEANUP_MARGIN).toMillis())));
        if (result == null) {
            throw unavailable();
        }
        return new Challenge(challengeId, prompt, properties.challengeTtl());
    }

    /**
     * Uses up the check. Empty when the ID is unknown, already used, expired,
     * or was started by another account.
     */
    public Optional<LivenessPrompt> take(String challengeId, UUID userId, Instant now) {
        if (!DeviceIds.isWellFormed(challengeId)) {
            return Optional.empty();
        }
        List<String> fields = call(() -> redis.execute(TAKE, List.of(key(challengeId))));
        if (fields == null) {
            throw unavailable();
        }
        if (fields.size() != 3 || fields.get(0) == null || fields.get(1) == null || fields.get(2) == null) {
            return Optional.empty();
        }
        boolean owner = fields.get(0).equals(userId.toString());
        boolean inTime = now.toEpochMilli() < Long.parseLong(fields.get(2));
        if (!owner || !inTime) {
            return Optional.empty();
        }
        return Optional.of(LivenessPrompt.valueOf(fields.get(1)));
    }

    private static String key(String challengeId) {
        return KEY_PREFIX + DeviceIds.hash(challengeId);
    }

    private <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.warn("Selfie check store unavailable, refusing the request: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private RetryLaterException unavailable() {
        return new RetryLaterException(RateLimitProblem.TEMPORARILY_UNAVAILABLE,
                rateLimitProperties.unavailableRetryAfter());
    }

    /** @param challengeId handed to the client; only its hash is stored */
    public record Challenge(String challengeId, LivenessPrompt prompt, Duration expiresIn) {

        @Override
        public String toString() {
            return "Challenge[prompt=" + prompt + "]";
        }
    }
}
