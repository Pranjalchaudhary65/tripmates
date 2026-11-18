package com.tripmate.identity.token;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A bad key must stop startup with a message that names the setting, and never prints the key. */
class AccessTokenKeysTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString("a".repeat(32).getBytes());

    private static final String KEY_B = Base64.getEncoder().encodeToString("b".repeat(32).getBytes());

    @Test
    void aMissingSigningKeyStopsStartup() {
        assertThatThrownBy(() -> properties("", null)).hasMessageContaining("JWT_SIGNING_KEY");
        assertThatThrownBy(() -> properties(null, null)).hasMessageContaining("JWT_SIGNING_KEY");
    }

    @Test
    void aKeyShorterThan256BitsIsRefused() {
        String short31 = Base64.getEncoder().encodeToString("s".repeat(31).getBytes());

        assertThatThrownBy(() -> new AccessTokenKeys(properties(short31, null)))
                .hasMessageContaining("JWT_SIGNING_KEY").hasMessageContaining("32 bytes")
                .hasMessageNotContaining(short31);
        assertThatThrownBy(() -> new AccessTokenKeys(properties(KEY_A, short31)))
                .hasMessageContaining("JWT_PREVIOUS_SIGNING_KEY");
    }

    @Test
    void aKeyThatIsNotBase64IsRefusedWithoutEchoingIt() {
        String notBase64 = "this is not base64 at all!!";

        assertThatThrownBy(() -> new AccessTokenKeys(properties(notBase64, null)))
                .hasMessageContaining("base64").hasMessageNotContaining(notBase64);
    }

    @Test
    void thePreviousKeyMustDifferFromTheCurrentOne() {
        assertThatThrownBy(() -> new AccessTokenKeys(properties(KEY_A, KEY_A)))
                .hasMessageContaining("must differ");
    }

    @Test
    void keyIdsAreDerivedFromTheKeyAndBothKeysVerify() {
        AccessTokenKeys keys = new AccessTokenKeys(properties(KEY_A, KEY_B));
        String currentKid = keys.current().keyId();

        assertThat(currentKid).hasSize(16).isEqualTo(new AccessTokenKeys(properties(KEY_A, null)).current().keyId());
        assertThat(keys.forVerification(currentKid)).isPresent();
        String previousKid = new AccessTokenKeys(properties(KEY_B, null)).current().keyId();
        assertThat(previousKid).isNotEqualTo(currentKid);
        assertThat(keys.forVerification(previousKid)).isPresent();
        assertThat(keys.forVerification("0000000000000000")).isEmpty();
        assertThat(keys.forVerification(null)).isEmpty();
    }

    @Test
    void propertiesNeverPrintTheKeys() {
        assertThat(properties(KEY_A, KEY_B).toString()).doesNotContain(KEY_A, KEY_B);
    }

    private static AccessTokenProperties properties(String signingKey, String previousKey) {
        return new AccessTokenProperties("tripmate", "tripmate-api", Duration.ofMinutes(15), Duration.ofSeconds(30),
                signingKey, previousKey);
    }
}
