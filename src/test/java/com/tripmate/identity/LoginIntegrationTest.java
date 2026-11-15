package com.tripmate.identity;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.service.DeviceIds;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.support.StubCaptcha;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@IntegrationTest
class LoginIntegrationTest {

    private static final String WRONG_PASSWORD = "not-the-right-password-7";

    private static final int LOCK_AFTER = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private MutableClock clock;

    private RegistrationApi registration;

    private LoginApi api;

    @BeforeEach
    void setUp() {
        LocalHttp http = new LocalHttp(port);
        registration = new RegistrationApi(http, mailpit);
        api = new LoginApi(http);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void correctPasswordSucceedsAndSetsAHardenedDeviceCookie() {
        String email = uniqueEmail();
        registration.registerVerified(email);

        HttpResponse<String> response = api.login(email.toUpperCase(), STRONG_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.userId")).isEqualTo(userId(email));
        assertThat(JsonPath.<String>read(response.body(), "$.verificationTier")).isEqualTo("EMAIL");
        assertThat(LoginApi.deviceSetCookie(response)).hasValueSatisfying(cookie -> assertThat(cookie)
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=34560000"));
        assertThat(DeviceIds.isWellFormed(LoginApi.deviceId(response))).isTrue();
    }

    @Test
    void unknownEmailWrongPasswordUnverifiedAndLockedAllLookTheSame() {
        String verified = uniqueEmail();
        registration.registerVerified(verified);
        String unverified = uniqueEmail();
        assertThat(registration.register(unverified).statusCode()).isEqualTo(202);
        String locked = uniqueEmail();
        registration.registerVerified(locked);
        for (int i = 0; i < LOCK_AFTER; i++) {
            api.login(locked, WRONG_PASSWORD);
        }

        List<HttpResponse<String>> refusals = List.of(
                api.login(uniqueEmail(), STRONG_PASSWORD),
                api.login(verified, WRONG_PASSWORD),
                api.login(unverified, STRONG_PASSWORD),
                api.login(locked, STRONG_PASSWORD));

        HttpResponse<String> first = refusals.getFirst();
        assertThat(first.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(first.body(), "$.code")).isEqualTo("invalid_credentials");
        for (HttpResponse<String> refusal : refusals) {
            assertThat(refusal.statusCode()).isEqualTo(first.statusCode());
            assertThat(bodyWithoutCorrelationId(refusal)).isEqualTo(bodyWithoutCorrelationId(first));
            assertThat(stableHeaders(refusal)).isEqualTo(stableHeaders(first));
            assertThat(LoginApi.deviceSetCookie(refusal)).isEmpty();
        }
        assertThat(latestFailureReason(verified)).isEqualTo("BAD_PASSWORD");
        assertThat(latestFailureReason(unverified)).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(latestFailureReason(locked)).isEqualTo("LOCKED");
    }

    @Test
    void theFifthFailureLocksTheAccountForAMinuteEvenAgainstTheRightPassword() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        for (int i = 0; i < LOCK_AFTER; i++) {
            assertThat(api.login(email, WRONG_PASSWORD).statusCode()).isEqualTo(401);
        }
        assertThat(failureReasons(email)).containsOnly("BAD_PASSWORD").hasSize(LOCK_AFTER);

        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(401);
        clock.advance(Duration.ofSeconds(59));
        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(failureReasons(email)).filteredOn("LOCKED"::equals).hasSize(2);

        clock.advance(Duration.ofSeconds(2));
        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void eachFailureAfterTheLockEndsDoublesTheNextLock() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        for (int i = 0; i < LOCK_AFTER; i++) {
            api.login(email, WRONG_PASSWORD);
        }
        clock.advance(Duration.ofSeconds(61));

        assertThat(api.login(email, WRONG_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(latestFailureReason(email)).isEqualTo("BAD_PASSWORD");

        clock.advance(Duration.ofSeconds(61));
        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(latestFailureReason(email)).isEqualTo("LOCKED");

        clock.advance(Duration.ofSeconds(60));
        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void aSuccessClearsTheFailureCount() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        for (int i = 0; i < LOCK_AFTER - 1; i++) {
            api.login(email, WRONG_PASSWORD);
        }

        // Counted as the fifth attempt, so it arms the lock - and then clears it by succeeding.
        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(api.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void aBrowserThatSignedInBeforeIsNotLockedOutByFailuresFromElsewhere() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        String ownersDevice = LoginApi.deviceId(api.login(email, STRONG_PASSWORD));

        LoginApi attacker = new LoginApi(new LocalHttp(port));
        for (int i = 0; i < LOCK_AFTER; i++) {
            attacker.login(email, WRONG_PASSWORD);
        }
        assertThat(attacker.login(email, STRONG_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(latestFailureReason(email)).isEqualTo("LOCKED");

        assertThat(api.loginFromDevice(email, STRONG_PASSWORD, ownersDevice).statusCode()).isEqualTo(200);
    }

    @Test
    void aMadeUpDeviceCookieDoesNotEscapeTheLock() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        for (int i = 0; i < LOCK_AFTER; i++) {
            api.login(email, WRONG_PASSWORD);
        }

        assertThat(api.loginFromDevice(email, STRONG_PASSWORD, DeviceIds.generate()).statusCode()).isEqualTo(401);
        assertThat(latestFailureReason(email)).isEqualTo("LOCKED");
    }

    @Test
    void loginHistoryRecordsBothOutcomesWithTheClientDetails() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        LoginApi client = new LoginApi(new LocalHttp(port, "203.0.113.7"));
        String unknown = uniqueEmail();

        client.login(email, WRONG_PASSWORD, "User-Agent", "TripMatesTest/1.0");
        HttpResponse<String> success = client.login(email, STRONG_PASSWORD, "User-Agent", "TripMatesTest/1.0");
        client.login(unknown, WRONG_PASSWORD);

        List<Map<String, Object>> rows = historyRows(email);
        assertThat(rows).hasSize(2);
        Map<String, Object> failure = rows.get(0);
        assertThat(failure.get("success")).isEqualTo(false);
        assertThat(failure.get("failure_reason")).isEqualTo("BAD_PASSWORD");
        assertThat(failure.get("user_id")).hasToString(userId(email));
        assertThat(failure.get("ip")).isEqualTo("203.0.113.7");
        assertThat(failure.get("user_agent")).isEqualTo("TripMatesTest/1.0");
        assertThat(failure.get("device_id_hash")).isNull();

        Map<String, Object> succeeded = rows.get(1);
        assertThat(succeeded.get("success")).isEqualTo(true);
        assertThat(succeeded.get("failure_reason")).isNull();
        assertThat(succeeded.get("user_id")).hasToString(userId(email));
        assertThat(succeeded.get("ip")).isEqualTo("203.0.113.7");
        assertThat(succeeded.get("device_id_hash")).isEqualTo(DeviceIds.hash(LoginApi.deviceId(success)));

        Map<String, Object> unknownRow = historyRows(unknown).getFirst();
        assertThat(unknownRow.get("user_id")).isNull();
        assertThat(unknownRow.get("failure_reason")).isEqualTo("UNKNOWN_EMAIL");
    }

    @Test
    void theClientIpIsTakenFromTheRightOfTheForwardedChainNotTheLeft() {
        String email = uniqueEmail();
        LoginApi spoofer = new LoginApi(new LocalHttp(port, "6.6.6.6, 203.0.113.9"));

        spoofer.login(email, WRONG_PASSWORD);

        assertThat(historyRows(email).getFirst().get("ip")).isEqualTo("203.0.113.9");
    }

    @Test
    void newDeviceAlertIsSentOnceAndNeverForAKnownBrowser() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        LoginApi laptop = new LoginApi(new LocalHttp(port, "203.0.113.20"));
        LoginApi phone = new LoginApi(new LocalHttp(port, "203.0.113.21"));

        String laptopDevice = LoginApi.deviceId(laptop.login(email, STRONG_PASSWORD));
        String phoneDevice = LoginApi.deviceId(phone.login(email, STRONG_PASSWORD));
        mailpit.awaitSubject(email, MailpitClient.NEW_DEVICE_SUBJECT);
        assertThat(mailpit.latestText(email, MailpitClient.NEW_DEVICE_SUBJECT)).contains("IP address: 203.0.113.21");

        phone.loginFromDevice(email, STRONG_PASSWORD, phoneDevice);
        laptop.loginFromDevice(email, STRONG_PASSWORD, laptopDevice);

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> mailpit.countWithSubject(email, MailpitClient.NEW_DEVICE_SUBJECT) == 1);
    }

    @Test
    void aCaptchaIsDemandedOnlyAfterRepeatedFailuresWhetherOrNotTheAccountExists() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        String unknown = uniqueEmail();

        for (String typed : List.of(email, unknown)) {
            HttpResponse<String> early = api.loginWithCaptcha(typed, WRONG_PASSWORD, StubCaptcha.FAILING_TOKEN);
            assertThat(JsonPath.<String>read(early.body(), "$.code")).isEqualTo("invalid_credentials");
            api.login(typed, WRONG_PASSWORD);
            api.login(typed, WRONG_PASSWORD);

            HttpResponse<String> challenged = api.loginWithCaptcha(typed, STRONG_PASSWORD, StubCaptcha.FAILING_TOKEN);
            assertThat(challenged.statusCode()).isEqualTo(401);
            assertThat(JsonPath.<String>read(challenged.body(), "$.code")).isEqualTo("captcha_required");
            assertThat(latestFailureReason(typed)).isEqualTo("CAPTCHA_FAILED");
        }

        // The refused CAPTCHA was not charged as a failure: a solved one now gets the fourth attempt.
        assertThat(api.loginWithCaptcha(email, STRONG_PASSWORD, "solved").statusCode()).isEqualTo(200);
    }

    @Test
    void unknownEmailTakesAboutAsLongAsAWrongPassword() {
        String email = uniqueEmail();
        registration.registerVerified(email);
        api.login(uniqueEmail(), WRONG_PASSWORD);
        api.login(email, WRONG_PASSWORD);

        List<Long> unknownNanos = new ArrayList<>();
        List<Long> wrongNanos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            unknownNanos.add(timed(() -> api.login(uniqueEmail(), WRONG_PASSWORD)));
            wrongNanos.add(timed(() -> api.login(email, WRONG_PASSWORD)));
        }

        // Without the dummy hash an unknown email skips Argon2id and answers
        // in a small fraction of the time; the bounds are loose on purpose,
        // since what they guard against is a ratio near zero, not jitter.
        double ratio = (double) median(unknownNanos) / median(wrongNanos);
        assertThat(ratio).isBetween(0.4, 2.5);
    }

    @Test
    void loginIsDocumentedWithItsRefusals() {
        DocumentContext doc = JsonPath.parse(new LocalHttp(port).get("/v3/api-docs").body());

        Map<String, Object> responses = doc.read("$.paths['" + LoginApi.LOGIN + "'].post.responses");
        assertThat(responses).containsKeys("200", "400", "401", "429", "503");
        for (String status : List.of("401", "429")) {
            Map<String, Object> content = doc.read("$.paths['" + LoginApi.LOGIN + "'].post.responses['" + status + "'].content");
            assertThat(content).as(status).containsKey("application/problem+json");
        }
    }

    private String userId(String email) {
        return jdbc.queryForObject("select id::text from users where email = ? and deleted_at is null",
                String.class, email);
    }

    private List<Map<String, Object>> historyRows(String email) {
        return jdbc.queryForList("""
                select user_id, host(ip_address) as ip, user_agent, device_id_hash, success, failure_reason
                from login_history where email_attempted = ? order by created_at, id""", email);
    }

    private List<String> failureReasons(String email) {
        return jdbc.queryForList("""
                select failure_reason from login_history
                where email_attempted = ? and not success order by created_at""", String.class, email);
    }

    private String latestFailureReason(String email) {
        return jdbc.queryForObject("""
                select failure_reason from login_history
                where email_attempted = ? and not success order by created_at desc limit 1""", String.class, email);
    }

    private static Map<String, Object> bodyWithoutCorrelationId(HttpResponse<String> response) {
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        assertThat(body).containsKey("correlationId");
        body.remove("correlationId");
        return body;
    }

    /** Every header except the two that differ on every response by design. */
    private static Map<String, List<String>> stableHeaders(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(response.headers().map());
        headers.remove("date");
        headers.remove("x-correlation-id");
        return headers;
    }

    private static long timed(Runnable call) {
        long start = System.nanoTime();
        call.run();
        return System.nanoTime() - start;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
}
