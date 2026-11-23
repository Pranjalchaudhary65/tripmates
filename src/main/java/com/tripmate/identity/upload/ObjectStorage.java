package com.tripmate.identity.upload;

import java.util.Optional;

/**
 * Where sanitised images live: never under a web root, never addressed by a
 * public URL. Callers pass keys made by {@link MediaKind#newKey()}; an
 * implementation refuses any other shape. A cloud bucket is a new
 * implementation of this interface, not a change to its callers.
 */
public interface ObjectStorage {

    /** Stores a new object. Keys are random, so an existing key is a bug and is refused. */
    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    /** Removing an object that is already gone is not an error, so a retried purge just finishes. */
    void delete(String key);
}
