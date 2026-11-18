package com.tripmate.identity.controller;

import com.tripmate.platform.web.ApiPaths;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The refresh token's cookie.
 *
 * <ul>
 *   <li>HttpOnly: page scripts, including injected ones, cannot read it. An
 *       XSS bug can use the session while the page is open, but cannot carry
 *       the refresh token away to keep using it later.</li>
 *   <li>Secure, SameSite=Strict: as for the device cookie.</li>
 *   <li>Path is the refresh endpoint alone. Login, logout and every other
 *       route never receive it, so no other code path can leak it or be
 *       tricked into acting on it.</li>
 *   <li>Max-Age is what is left of the session's fixed lifetime, so the
 *       cookie and the session end together.</li>
 * </ul>
 *
 * <p>It sits beside {@code tm_device} without overlap: different name,
 * different job. The device cookie outlives sessions and says "this browser
 * has signed in before"; this one is the session.
 */
final class RefreshCookie {

    static final String NAME = "tm_refresh";

    static final String PATH = ApiPaths.V1 + SessionController.BASE + SessionController.REFRESH;

    private RefreshCookie() {
    }

    static ResponseCookie issue(String refreshToken, Duration maxAge) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    /** Same name, path and attributes as the one set, or the browser keeps the old cookie. */
    static ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH);
    }
}
