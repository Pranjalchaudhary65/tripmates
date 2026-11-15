package com.tripmate.identity.service;

import com.tripmate.platform.web.ClientAddress;

import java.util.Objects;

/**
 * Who is attempting the login, as far as the request shows.
 *
 * @param address   resolved client IP
 * @param userAgent raw User-Agent header, may be null
 * @param deviceId  the device cookie's value if it was well-formed, else null
 */
public record LoginClient(ClientAddress address, String userAgent, String deviceId) {

    public LoginClient {
        Objects.requireNonNull(address, "address");
        if (deviceId != null && !DeviceIds.isWellFormed(deviceId)) {
            throw new IllegalArgumentException("deviceId must be well-formed or null");
        }
    }

    /** The device ID decides whether this browser gets its own lockout counter; it stays out of logs. */
    @Override
    public String toString() {
        return "LoginClient[address=" + address.hostAddress() + "]";
    }
}
