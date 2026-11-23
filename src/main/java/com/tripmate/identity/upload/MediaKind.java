package com.tripmate.identity.upload;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The kinds of stored image, each under its own prefix. A key is the prefix
 * and a random UUID: it says nothing about whose image it is, and cannot be
 * guessed from another one.
 */
public enum MediaKind {

    PROFILE_PHOTO("profile-photos"),

    SELFIE("selfies");

    /** Every key any kind can produce, and nothing else: no "..", no slashes beyond the one. */
    static final Pattern KEY = Pattern.compile("^(profile-photos|selfies)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$");

    private final String prefix;

    MediaKind(String prefix) {
        this.prefix = prefix;
    }

    public String newKey() {
        return prefix + "/" + UUID.randomUUID() + ".jpg";
    }

    public static boolean isWellFormed(String key) {
        return key != null && KEY.matcher(key).matches();
    }
}
