package com.tripmate.identity.mail;

import java.time.Instant;

/**
 * A successful login from a browser that has never signed in to this account
 * before. Published inside the transaction that recorded the login, so the
 * alert goes out only once that row is committed.
 */
public record NewDeviceLoginEvent(String email, String fullName, Instant at, String ipAddress) {

    @Override
    public String toString() {
        return "NewDeviceLoginEvent[at=" + at + "]";
    }
}
