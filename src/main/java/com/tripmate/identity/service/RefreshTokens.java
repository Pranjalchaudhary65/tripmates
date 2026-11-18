package com.tripmate.identity.service;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refresh token values: {@code {familyId}.{secret}}, the secret being 32
 * random bytes as unpadded base64url. The family ID in front lets a refresh
 * find its family in one lookup; the secret is what proves possession.
 *
 * <p>Only {@link #hash} of the whole value is stored. Hashing the family ID
 * along with the secret binds the two: a secret is worthless under any
 * other family ID.
 */
final class RefreshTokens {

    private static final Pattern WELL_FORMED = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{43})");

    private RefreshTokens() {
    }

    static String generate(UUID familyId) {
        return familyId + "." + DeviceIds.generate();
    }

    /** The family a well-formed token claims to belong to; empty for anything else, which is treated as no token. */
    static Optional<UUID> familyOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Matcher matcher = WELL_FORMED.matcher(token);
        return matcher.matches() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
    }

    /** SHA-256, unkeyed: 256 random bits leave no candidate set to precompute, as for device IDs. */
    static String hash(String token) {
        return DeviceIds.sha256Hex(token);
    }
}
