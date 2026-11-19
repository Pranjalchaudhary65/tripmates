package com.tripmate.identity.service;

import java.util.Objects;

/**
 * What a correct password led to: either a session, or a challenge for the
 * second factor and nothing else - no session, no cookie, no device ID.
 *
 * @param deviceId  the ID the browser's device cookie must hold from now on; null with a challenge
 * @param session   the session the login opened; null with a challenge
 * @param challenge the second-factor challenge; null with a session
 */
public record LoginResult(String deviceId, SessionTokens session, SecondFactorChallenge challenge) {

    public LoginResult {
        if ((session == null) == (challenge == null)) {
            throw new IllegalArgumentException("A login result is a session or a challenge, never both or neither");
        }
        if (session != null) {
            Objects.requireNonNull(deviceId, "deviceId");
        } else if (deviceId != null) {
            throw new IllegalArgumentException("No device cookie is set before the second factor passes");
        }
    }

    public static LoginResult signedIn(String deviceId, SessionTokens session) {
        return new LoginResult(deviceId, session, null);
    }

    public static LoginResult secondFactorRequired(SecondFactorChallenge challenge) {
        return new LoginResult(null, null, challenge);
    }

    public boolean isSignedIn() {
        return session != null;
    }

    @Override
    public String toString() {
        return isSignedIn() ? "LoginResult[" + session + "]" : "LoginResult[" + challenge + "]";
    }
}
