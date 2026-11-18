package com.tripmate.identity;

import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds JWTs by hand, byte for byte, so a test can produce exactly the
 * malformed or malicious token an attacker would: any {@code alg} in the
 * header whatever the signature really is, any key, any claims. A JOSE
 * library would refuse to create most of these, which is the point.
 */
final class ForgedTokens {

    /** tripmate.identity.access-token.signing-key in application-test.yml. */
    static final byte[] CURRENT_KEY = Base64.getDecoder().decode("dGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS1jdXJyZW50");

    /** tripmate.identity.access-token.previous-signing-key in application-test.yml. */
    static final byte[] PREVIOUS_KEY = Base64.getDecoder().decode("dGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS1wcmV2aW91cw==");

    private ForgedTokens() {
    }

    /** The kid the server derives for a key: the first 8 bytes of its SHA-256, in hex. */
    static String kid(byte[] key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key), 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** The claims of a real token, as a mutable map, so a test can change one thing and re-sign. */
    static Map<String, Object> claimsOf(String token) {
        try {
            return new LinkedHashMap<>(SignedJWT.parse(token).getJWTClaimsSet().toJSONObject());
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }

    static Map<String, Object> claimsOf(String token, Consumer<Map<String, Object>> change) {
        Map<String, Object> claims = claimsOf(token);
        change.accept(claims);
        return claims;
    }

    /** A standard access token header: typ at+jwt and the given alg and kid (left out when null). */
    static Map<String, Object> header(String alg, String kid) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("typ", "at+jwt");
        if (kid != null) {
            header.put("kid", kid);
        }
        return header;
    }

    /**
     * Signs with HMAC using {@code macAlgorithm} (HmacSHA256/384/512),
     * whatever the header's {@code alg} claims.
     */
    static String hmac(Map<String, Object> header, Map<String, Object> claims, byte[] key, String macAlgorithm) {
        String signingInput = encode(header) + "." + encode(claims);
        try {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(key, macAlgorithm));
            return signingInput + "." + Base64URL.encode(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hs256(Map<String, Object> header, Map<String, Object> claims, byte[] key) {
        return hmac(header, claims, key, "HmacSHA256");
    }

    /** {@code alg: none} with an empty signature, as RFC 7519 §6.1 writes an unsecured JWT. */
    static String unsigned(Map<String, Object> claims) {
        Map<String, Object> header = header("none", kid(CURRENT_KEY));
        return encode(header) + "." + encode(claims) + ".";
    }

    private static String encode(Map<String, Object> json) {
        return Base64URL.encode(JSONObjectUtils.toJSONString(json).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
