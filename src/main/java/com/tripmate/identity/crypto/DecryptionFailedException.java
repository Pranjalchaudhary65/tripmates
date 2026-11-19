package com.tripmate.identity.crypto;

/**
 * A stored ciphertext could not be opened: it was altered, copied from
 * another row or column, sealed under a different key, or its data key has
 * been shredded. Never a client error; it means the data is gone or the
 * deployment is misconfigured, and the request fails with a 500.
 */
public class DecryptionFailedException extends RuntimeException {

    public DecryptionFailedException(String message) {
        super(message);
    }
}
