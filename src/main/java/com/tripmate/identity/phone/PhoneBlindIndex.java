package com.tripmate.identity.phone;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 of an E.164 number under {@code PHONE_BLIND_INDEX_KEY}: the
 * value users.phone_blind_index holds, so "is this number already verified
 * on another account" is an equality match on an index instead of a
 * decryption of every row.
 *
 * <p>Keyed, not a plain hash: India has under ten billion mobile numbers, and
 * an unkeyed SHA-256 of every one of them is an afternoon on a laptop, after
 * which a leaked table maps every index back to its number. The key lives in
 * the environment, apart from the database, and apart from the OTP key too,
 * so one leaking does not expose the other's data.
 */
@Component
@EnableConfigurationProperties(PhoneProperties.class)
public class PhoneBlindIndex {

    private static final String ALGORITHM = "HmacSHA256";

    private static final String CONTEXT = "users.phone|";

    private final SecretKeySpec key;

    public PhoneBlindIndex(PhoneProperties properties) {
        this.key = new SecretKeySpec(properties.blindIndexKeyBytes(), ALGORITHM);
    }

    /** @param e164 a number already normalised by {@link PhoneNumbers#normalise} */
    public String of(String e164) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((CONTEXT + e164).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is a mandatory JDK algorithm", e);
        }
    }
}
