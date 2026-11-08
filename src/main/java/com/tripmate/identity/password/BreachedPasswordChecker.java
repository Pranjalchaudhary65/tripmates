package com.tripmate.identity.password;

/**
 * Answers whether a password appears in a known breach corpus. Implementations
 * must never send the password, or a full hash of it, off the server.
 */
public interface BreachedPasswordChecker {

    BreachStatus check(String password);

    enum BreachStatus {
        BREACHED,
        NOT_FOUND,
        /** The corpus could not be consulted; the caller decides what that means. */
        UNAVAILABLE
    }
}
