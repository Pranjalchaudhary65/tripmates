package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.tripmate.support.LocalHttp;

import java.net.http.HttpResponse;
import java.text.ParseException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls the session endpoints over real HTTP the way the React app would. */
final class SessionApi {

    static final String REFRESH = "/api/v1/auth/refresh";

    static final String LOGOUT = "/api/v1/auth/logout";

    static final String SESSIONS = "/api/v1/auth/sessions";

    static final String REVOKE_ALL = "/api/v1/auth/sessions/revoke-all";

    static final String ME = "/api/v1/users/me";

    static final String REFRESH_COOKIE = "tm_refresh";

    static final String CSRF_HEADER = "X-TripMates-CSRF";

    private final LocalHttp http;

    private final LoginApi login;

    SessionApi(LocalHttp http) {
        this.http = http;
        this.login = new LoginApi(http);
    }

    /** Logs in and returns the session it opened; fails the test if login does not succeed. */
    Session signIn(String email, String password, String... headers) {
        HttpResponse<String> response = login.login(email, password, headers);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Session(JsonPath.read(response.body(), "$.session.accessToken"), refreshToken(response));
    }

    HttpResponse<String> refresh(String refreshToken, String... extraHeaders) {
        return http.post(REFRESH, concat(new String[] {
                "Cookie", REFRESH_COOKIE + "=" + refreshToken, CSRF_HEADER, "1"}, extraHeaders));
    }

    HttpResponse<String> refreshWithoutCsrfHeader(String refreshToken) {
        return http.post(REFRESH, "Cookie", REFRESH_COOKIE + "=" + refreshToken);
    }

    HttpResponse<String> me(String accessToken) {
        return http.get(ME, "Authorization", "Bearer " + accessToken);
    }

    HttpResponse<String> logout(String accessToken) {
        return http.post(LOGOUT, "Authorization", "Bearer " + accessToken);
    }

    HttpResponse<String> revokeAll(String accessToken) {
        return http.post(REVOKE_ALL, "Authorization", "Bearer " + accessToken);
    }

    HttpResponse<String> sessions(String accessToken) {
        return http.get(SESSIONS, "Authorization", "Bearer " + accessToken);
    }

    /** The full Set-Cookie header for the refresh cookie, if the response set or cleared it. */
    static Optional<String> refreshSetCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(REFRESH_COOKIE + "="))
                .findFirst();
    }

    static String refreshToken(HttpResponse<String> response) {
        String header = refreshSetCookie(response)
                .orElseThrow(() -> new AssertionError("No refresh cookie in " + response.headers()));
        String value = header.substring(REFRESH_COOKIE.length() + 1, header.indexOf(';'));
        assertThat(value).as("refresh cookie was cleared, not set").isNotEmpty();
        return value;
    }

    static boolean clearsRefreshCookie(HttpResponse<String> response) {
        return refreshSetCookie(response)
                .filter(header -> header.startsWith(REFRESH_COOKIE + "=;") && header.contains("Max-Age=0"))
                .isPresent();
    }

    static String accessTokenOfRefresh(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.accessToken");
    }

    static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = new String[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    record Session(String accessToken, String refreshToken) {

        String sessionId() {
            return claim("sid");
        }

        String userId() {
            return claim("sub");
        }

        private String claim(String name) {
            try {
                return SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim(name);
            } catch (ParseException e) {
                throw new AssertionError("Access token is not a JWT", e);
            }
        }
    }
}
