package com.tripmate.identity.phone;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

/**
 * Phone verification settings. Code lifetime, attempts, cooldown and the
 * daily cap are shared with email codes ({@code tripmate.identity.verification}).
 *
 * @param blindIndexKey base64, at least 32 bytes: the HMAC key of
 *                      users.phone_blind_index. No default. Changing it makes
 *                      every stored index unmatchable until recomputed from
 *                      the decrypted numbers, so it is not rotated casually.
 * @param smsProvider   which {@link PhoneCodeSender} delivers codes; an
 *                      unknown value stops startup
 */
@ConfigurationProperties("tripmate.identity.phone")
public record PhoneProperties(String blindIndexKey, String smsProvider) {

    static final int MIN_KEY_BYTES = 32;

    public PhoneProperties {
        if (blindIndexKey == null || blindIndexKey.isBlank()) {
            throw new IllegalArgumentException(
                    "tripmate.identity.phone.blind-index-key (PHONE_BLIND_INDEX_KEY) is required");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(blindIndexKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PHONE_BLIND_INDEX_KEY must be base64", e);
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("PHONE_BLIND_INDEX_KEY must decode to at least "
                    + MIN_KEY_BYTES + " bytes");
        }
        if (smsProvider == null || smsProvider.isBlank()) {
            throw new IllegalArgumentException("tripmate.identity.phone.sms-provider is required");
        }
    }

    byte[] blindIndexKeyBytes() {
        return Base64.getDecoder().decode(blindIndexKey.trim());
    }

    /** Never print the key, whatever logs a bound properties object. */
    @Override
    public String toString() {
        return "PhoneProperties[blindIndexKey=****, smsProvider=" + smsProvider + "]";
    }
}
