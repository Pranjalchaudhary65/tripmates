package com.tripmate.identity.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * @param signingKey base64, at least 32 bytes: signs media URLs. No default.
 *                   Changing it only makes URLs already handed out stop
 *                   working, so it can be rotated at any time.
 * @param urlTtl     how long a signed URL works
 */
@ConfigurationProperties("tripmate.media")
public record MediaUrlProperties(String signingKey, Duration urlTtl) {

    static final int MIN_KEY_BYTES = 32;

    public MediaUrlProperties {
        Objects.requireNonNull(urlTtl, "tripmate.media.url-ttl is required");
        if (urlTtl.isNegative() || urlTtl.isZero()) {
            throw new IllegalArgumentException("tripmate.media.url-ttl must be positive");
        }
        if (signingKey == null || signingKey.isBlank()) {
            throw new IllegalArgumentException("tripmate.media.signing-key (MEDIA_URL_SIGNING_KEY) is required");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(signingKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MEDIA_URL_SIGNING_KEY must be base64", e);
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("MEDIA_URL_SIGNING_KEY must decode to at least "
                    + MIN_KEY_BYTES + " bytes");
        }
    }

    byte[] signingKeyBytes() {
        return Base64.getDecoder().decode(signingKey.trim());
    }

    @Override
    public String toString() {
        return "MediaUrlProperties[signingKey=****, urlTtl=" + urlTtl + "]";
    }
}
