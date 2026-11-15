package com.tripmate.identity.controller;

import com.tripmate.identity.service.DeviceIds;
import com.tripmate.platform.web.ApiPaths;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The cookie that lets a browser be recognised on its next sign-in.
 *
 * <ul>
 *   <li>HttpOnly: page scripts, including injected ones, cannot read it.</li>
 *   <li>Secure: never sent over plain http (browsers make an exception for
 *       localhost, which is what local development uses).</li>
 *   <li>SameSite=Strict: never attached to a request another site starts,
 *       so a cross-site login request cannot borrow this browser's lockout
 *       counter.</li>
 *   <li>Path limited to the auth routes: no other endpoint receives it.</li>
 *   <li>400 days, the longest lifetime browsers honour.</li>
 * </ul>
 */
final class DeviceCookie {

    static final String NAME = "tm_device";

    static final String PATH = ApiPaths.V1 + LoginController.BASE;

    private static final Duration MAX_AGE = Duration.ofDays(400);

    private DeviceCookie() {
    }

    static ResponseCookie issue(String deviceId) {
        return ResponseCookie.from(NAME, deviceId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(MAX_AGE)
                .build();
    }

    /** A malformed or tampered value is treated as no cookie at all. */
    static String valueOrNull(String raw) {
        return DeviceIds.isWellFormed(raw) ? raw : null;
    }
}
