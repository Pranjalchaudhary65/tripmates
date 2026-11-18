package com.tripmate.identity.service;

import java.util.Objects;

/**
 * @param deviceId the ID the browser's device cookie must hold from now on
 * @param session  the session the login opened
 */
public record LoginResult(String deviceId, SessionTokens session) {

    public LoginResult {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(session, "session");
    }

    @Override
    public String toString() {
        return "LoginResult[" + session + "]";
    }
}
