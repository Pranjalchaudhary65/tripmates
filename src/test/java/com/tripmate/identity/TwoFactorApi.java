package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MutableClock;
import com.tripmate.support.TestAuthenticator;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls the 2FA endpoints over real HTTP the way the React app would. */
public final class TwoFactorApi {

    static final String STATUS = "/api/v1/auth/2fa";

    static final String SETUP = "/api/v1/auth/2fa/setup";

    static final String CONFIRM = "/api/v1/auth/2fa/confirm";

    static final String DISABLE = "/api/v1/auth/2fa/disable";

    static final String BACKUP_CODES = "/api/v1/auth/2fa/backup-codes";

    static final String SECOND_FACTOR = "/api/v1/auth/login/second-factor";

    private final LocalHttp http;

    public TwoFactorApi(LocalHttp http) {
        this.http = http;
    }

    HttpResponse<String> status(String accessToken) {
        return http.get(STATUS, bearer(accessToken));
    }

    public HttpResponse<String> setup(String accessToken, String password) {
        return http.postJson(SETUP, """
                {"password":"%s"}""".formatted(password), bearer(accessToken));
    }

    HttpResponse<String> confirm(String accessToken, String code) {
        return http.postJson(CONFIRM, """
                {"code":"%s"}""".formatted(code), bearer(accessToken));
    }

    HttpResponse<String> disable(String accessToken, String password, String code) {
        return http.postJson(DISABLE, """
                {"password":"%s","code":"%s"}""".formatted(password, code), bearer(accessToken));
    }

    HttpResponse<String> regenerate(String accessToken, String password, String code) {
        return http.postJson(BACKUP_CODES, """
                {"password":"%s","code":"%s"}""".formatted(password, code), bearer(accessToken));
    }

    public HttpResponse<String> secondFactor(String challengeId, String code, String... headers) {
        return http.postJson(SECOND_FACTOR, """
                {"challengeId":"%s","code":"%s"}""".formatted(challengeId, code), headers);
    }

    /** Sets up and confirms 2FA from {@code accessToken}'s session; fails the test if either step fails. */
    public Enrolment enrol(String accessToken, String password, MutableClock clock) {
        HttpResponse<String> setup = setup(accessToken, password);
        assertThat(setup.statusCode()).as(setup.body()).isEqualTo(200);
        TestAuthenticator authenticator = new TestAuthenticator(JsonPath.read(setup.body(), "$.secret"), clock);
        HttpResponse<String> confirmed = confirm(accessToken, authenticator.nextCode());
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return new Enrolment(authenticator, JsonPath.read(confirmed.body(), "$.backupCodes"));
    }

    public static String challengeId(HttpResponse<String> loginResponse) {
        assertThat(JsonPath.<String>read(loginResponse.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
        return JsonPath.read(loginResponse.body(), "$.secondFactor.challengeId");
    }

    private static String[] bearer(String accessToken) {
        return new String[] {"Authorization", "Bearer " + accessToken};
    }

    public record Enrolment(TestAuthenticator authenticator, List<String> backupCodes) {
    }
}
