package com.tripmate.identity.upload;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Short-lived links to one stored image, handed only to someone allowed to
 * see it at that moment: the owner, for their own profile photo, or a
 * reviewer, for a flagged selfie. The token is
 * {@code base64url(key|expiry).base64url(HMAC-SHA256)}: the server keeps no
 * record of it, and nothing about it can be changed (another key, a later
 * expiry) without the signature failing.
 *
 * <p>An {@code <img>} tag cannot send a bearer header, which is why images
 * are reached this way rather than through an authenticated route. The
 * signature is the authorisation, so links are kept short-lived and are
 * never logged or stored.
 */
@Component
@EnableConfigurationProperties(MediaUrlProperties.class)
public class SignedMediaUrls {

    /** Relative to the versioned API base, like every other path a client builds. */
    public static final String PATH_PREFIX = "/media/";

    private static final String ALGORITHM = "HmacSHA256";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final int MAX_TOKEN_LENGTH = 256;

    private final SecretKeySpec key;

    private final MediaUrlProperties properties;

    private final Clock clock;

    public SignedMediaUrls(MediaUrlProperties properties, Clock clock) {
        this.key = new SecretKeySpec(properties.signingKeyBytes(), ALGORITHM);
        this.properties = properties;
        this.clock = clock;
    }

    public SignedMediaUrl sign(String objectKey) {
        if (!MediaKind.isWellFormed(objectKey)) {
            throw new IllegalArgumentException("Not an object key");
        }
        Instant expiresAt = clock.instant().plus(properties.urlTtl());
        byte[] payload = (objectKey + "|" + expiresAt.getEpochSecond()).getBytes(StandardCharsets.UTF_8);
        String token = ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(mac(payload));
        return new SignedMediaUrl(PATH_PREFIX + token, expiresAt);
    }

    /** @return the object key, when the token is genuine and has not expired */
    public Optional<String> verify(String token) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(token.substring(0, dot));
            signature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(payload), signature)) {
            return Optional.empty();
        }
        String text = new String(payload, StandardCharsets.UTF_8);
        int bar = text.lastIndexOf('|');
        if (bar <= 0) {
            return Optional.empty();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(text.substring(bar + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String objectKey = text.substring(0, bar);
        if (clock.instant().getEpochSecond() >= expiresAt || !MediaKind.isWellFormed(objectKey)) {
            return Optional.empty();
        }
        return Optional.of(objectKey);
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is a mandatory JDK algorithm", e);
        }
    }

    /**
     * @param path      relative to the API base, e.g. {@code /media/...}
     * @param expiresAt when the link stops working
     */
    public record SignedMediaUrl(String path, Instant expiresAt) {

        @Override
        public String toString() {
            return "SignedMediaUrl[expiresAt=" + expiresAt + "]";
        }
    }
}
