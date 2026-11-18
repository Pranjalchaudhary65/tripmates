package com.tripmate.identity.token;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The key new access tokens are signed with, and every key a token may still
 * be verified with, looked up by {@code kid}.
 *
 * <p>Rotation: generate a new key, move the old value to
 * {@code JWT_PREVIOUS_SIGNING_KEY}, put the new one in
 * {@code JWT_SIGNING_KEY}, restart. Tokens already issued carry the old kid
 * and keep working until they expire; new ones carry the new kid. After one
 * access-token lifetime, nothing signed with the old key is still valid and
 * the previous key can be removed. If a key leaks, skip the overlap: replace
 * it without keeping it as previous, and every token it signed fails at once.
 */
@Component
@EnableConfigurationProperties(AccessTokenProperties.class)
public class AccessTokenKeys {

    private final SigningKey current;

    private final Map<String, SigningKey> verificationKeys;

    public AccessTokenKeys(AccessTokenProperties properties) {
        this.current = SigningKey.fromBase64(properties.signingKey(), "JWT_SIGNING_KEY");
        Map<String, SigningKey> keys = new LinkedHashMap<>();
        keys.put(current.keyId(), current);
        String previous = properties.previousSigningKey();
        if (previous != null && !previous.isBlank()) {
            SigningKey old = SigningKey.fromBase64(previous, "JWT_PREVIOUS_SIGNING_KEY");
            if (keys.putIfAbsent(old.keyId(), old) != null) {
                throw new IllegalArgumentException("JWT_PREVIOUS_SIGNING_KEY must differ from JWT_SIGNING_KEY");
            }
        }
        this.verificationKeys = Map.copyOf(keys);
    }

    SigningKey current() {
        return current;
    }

    Optional<SigningKey> forVerification(String keyId) {
        return keyId == null ? Optional.empty() : Optional.ofNullable(verificationKeys.get(keyId));
    }
}
