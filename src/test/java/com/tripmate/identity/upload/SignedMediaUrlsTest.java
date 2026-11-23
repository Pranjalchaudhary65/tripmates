package com.tripmate.identity.upload;

import com.tripmate.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedMediaUrlsTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("x".repeat(32).getBytes());

    private final MutableClock clock = new MutableClock();

    private final SignedMediaUrls urls = new SignedMediaUrls(new MediaUrlProperties(KEY, Duration.ofMinutes(5)), clock);

    private final String objectKey = MediaKind.SELFIE.newKey();

    @Test
    void aFreshLinkLeadsBackToItsObject() {
        SignedMediaUrls.SignedMediaUrl link = urls.sign(objectKey);

        assertThat(link.path()).startsWith("/media/");
        assertThat(urls.verify(token(link))).hasValue(objectKey);
    }

    @Test
    void aLinkStopsWorkingWhenItExpires() {
        SignedMediaUrls.SignedMediaUrl link = urls.sign(objectKey);

        clock.advance(Duration.ofMinutes(5));

        assertThat(urls.verify(token(link))).isEmpty();
    }

    @Test
    void aLinkPointedAtAnotherObjectOrGivenALaterExpiryFailsItsSignature() {
        String token = token(urls.sign(objectKey));
        String signature = token.substring(token.indexOf('.') + 1);
        String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8);
        String otherObject = payload.replace(objectKey, MediaKind.SELFIE.newKey());
        String later = payload.substring(0, payload.lastIndexOf('|') + 1) + (Long.MAX_VALUE / 2);

        assertThat(urls.verify(encode(otherObject) + "." + signature)).isEmpty();
        assertThat(urls.verify(encode(later) + "." + signature)).isEmpty();
    }

    @Test
    void aLinkSignedWithAnotherKeyIsRefused() {
        SignedMediaUrls other = new SignedMediaUrls(new MediaUrlProperties(OTHER_KEY, Duration.ofMinutes(5)), clock);

        assertThat(urls.verify(token(other.sign(objectKey)))).isEmpty();
    }

    @Test
    void malformedTokensAreRefused() {
        assertThat(urls.verify(null)).isEmpty();
        assertThat(urls.verify("")).isEmpty();
        assertThat(urls.verify("no-dot")).isEmpty();
        assertThat(urls.verify("a.b.c")).isEmpty();
        assertThat(urls.verify("!!!.???")).isEmpty();
        assertThat(urls.verify("x".repeat(300) + ".y")).isEmpty();
    }

    @Test
    void onlyWellFormedKeysAreEverSigned() {
        assertThatThrownBy(() -> urls.sign("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> urls.sign("selfies/" + UUID.randomUUID() + ".png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aShortOrMissingSigningKeyStopsStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new MediaUrlProperties(shortKey, Duration.ofMinutes(5)))
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> new MediaUrlProperties("", Duration.ofMinutes(5)))
                .hasMessageContaining("MEDIA_URL_SIGNING_KEY");
    }

    private static String token(SignedMediaUrls.SignedMediaUrl link) {
        return link.path().substring(SignedMediaUrls.PATH_PREFIX.length());
    }

    private static String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
