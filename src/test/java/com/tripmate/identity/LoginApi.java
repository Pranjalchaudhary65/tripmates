package com.tripmate.identity;

import com.tripmate.support.LocalHttp;

import java.net.http.HttpResponse;
import java.util.Optional;

/** Calls the login endpoint over real HTTP the way a browser would. */
final class LoginApi {

    static final String LOGIN = "/api/v1/auth/login";

    static final String DEVICE_COOKIE = "tm_device";

    private final LocalHttp http;

    LoginApi(LocalHttp http) {
        this.http = http;
    }

    HttpResponse<String> login(String email, String password, String... headers) {
        return http.postJson(LOGIN, """
                {"email":"%s","password":"%s"}""".formatted(email, password), headers);
    }

    HttpResponse<String> loginWithCaptcha(String email, String password, String captchaToken) {
        return http.postJson(LOGIN, """
                {"email":"%s","password":"%s","captchaToken":"%s"}""".formatted(email, password, captchaToken));
    }

    /** Signs in from the browser that holds {@code deviceId}. */
    HttpResponse<String> loginFromDevice(String email, String password, String deviceId) {
        return login(email, password, "Cookie", DEVICE_COOKIE + "=" + deviceId);
    }

    /** The full Set-Cookie header for the device cookie, if the response set one. */
    static Optional<String> deviceSetCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(DEVICE_COOKIE + "="))
                .findFirst();
    }

    static String deviceId(HttpResponse<String> response) {
        String header = deviceSetCookie(response)
                .orElseThrow(() -> new AssertionError("No device cookie in " + response.headers()));
        return header.substring(DEVICE_COOKIE.length() + 1, header.indexOf(';'));
    }
}
