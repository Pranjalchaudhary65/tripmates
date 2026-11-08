package com.tripmate.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * Email verification code rules. {@code hmacKey} is a secret with no default:
 * an unset or short key stops startup rather than letting codes be hashed
 * under a guessable key.
 *
 * @param codeTtl         how long a code stays valid
 * @param maxAttempts     wrong guesses allowed per code before it is dead
 * @param resendCooldown  minimum gap between two codes for the same account
 * @param maxCodesPerDay  codes one account can be sent in any 24 hours
 * @param hmacKey         base64 key, at least 32 bytes, used to hash codes at rest
 */
@ConfigurationProperties("tripmate.identity.verification")
public record VerificationProperties(Duration codeTtl, int maxAttempts, Duration resendCooldown,
                                     int maxCodesPerDay, String hmacKey) {

    static final int MIN_KEY_BYTES = 32;

    public VerificationProperties {
        Objects.requireNonNull(codeTtl, "tripmate.identity.verification.code-ttl is required");
        Objects.requireNonNull(resendCooldown, "tripmate.identity.verification.resend-cooldown is required");
        if (maxAttempts < 1 || maxAttempts > Short.MAX_VALUE) {
            throw new IllegalArgumentException("tripmate.identity.verification.max-attempts must be 1..32767");
        }
        if (maxCodesPerDay < 1) {
            throw new IllegalArgumentException("tripmate.identity.verification.max-codes-per-day must be positive");
        }
        if (hmacKey == null || hmacKey.isBlank()) {
            throw new IllegalArgumentException("tripmate.identity.verification.hmac-key (OTP_HMAC_KEY) is required");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(hmacKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OTP_HMAC_KEY must be base64", e);
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("OTP_HMAC_KEY must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
    }

    byte[] hmacKeyBytes() {
        return Base64.getDecoder().decode(hmacKey.trim());
    }

    /** Never print the key, whatever logs a bound properties object. */
    @Override
    public String toString() {
        return "VerificationProperties[codeTtl=" + codeTtl + ", maxAttempts=" + maxAttempts
                + ", resendCooldown=" + resendCooldown + ", maxCodesPerDay=" + maxCodesPerDay + ", hmacKey=****]";
    }
}
