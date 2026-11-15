package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.service.AuthRateLimitProperties;
import com.tripmate.identity.service.LoginProperties;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AuthRateLimitIntegrationTest {

    private static final String WRONG_PASSWORD = "not-the-right-password-7";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private AuthRateLimitProperties limits;

    @Autowired
    private LoginProperties loginProperties;

    @Test
    void oneIpIsRefusedWith429OnceItsLoginBucketIsEmpty() {
        LoginApi api = new LoginApi(new LocalHttp(port));
        for (long i = 0; i < limits.loginPerIp().capacity(); i++) {
            assertThat(api.login(uniqueEmail(), WRONG_PASSWORD).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> limited = api.login(uniqueEmail(), WRONG_PASSWORD);

        assertIsRateLimited(limited);
        assertThat(new LoginApi(new LocalHttp(port)).login(uniqueEmail(), WRONG_PASSWORD).statusCode())
                .as("another IP has its own bucket")
                .isEqualTo(401);
    }

    @Test
    void oneEmailIsRefusedWith429FromAnyIpAndTheSameWayWhetherOrNotItHasAnAccount() {
        String registered = uniqueEmail();
        new RegistrationApi(new LocalHttp(port), mailpit).registerVerified(registered);
        String unregistered = uniqueEmail();

        List<HttpResponse<String>> limited = new ArrayList<>();
        for (String email : List.of(registered, unregistered)) {
            for (long i = 0; i < limits.loginPerAccount().capacity(); i++) {
                assertThat(freshClient().login(email, WRONG_PASSWORD).statusCode()).isEqualTo(401);
            }
            limited.add(freshClient().login(email, WRONG_PASSWORD));
        }

        limited.forEach(AuthRateLimitIntegrationTest::assertIsRateLimited);
        assertThat(withoutCorrelationId(limited.get(0))).isEqualTo(withoutCorrelationId(limited.get(1)));
    }

    @Test
    void parallelRequestsFromOneIpNeverGetPastTheBucket() throws Exception {
        LoginApi api = new LoginApi(new LocalHttp(port));
        int capacity = Math.toIntExact(limits.loginPerIp().capacity());

        List<Integer> statuses = inParallel(2 * capacity, () -> api.login(uniqueEmail(), WRONG_PASSWORD).statusCode());

        assertThat(statuses).filteredOn(status -> status == 401).hasSize(capacity);
        assertThat(statuses).filteredOn(status -> status == 429).hasSize(capacity);
    }

    @Test
    void parallelWrongGuessesNeverGetMorePasswordChecksThanTheLockoutAllows() throws Exception {
        String email = uniqueEmail();
        new RegistrationApi(new LocalHttp(port), mailpit).registerVerified(email);
        int capacity = Math.toIntExact(limits.loginPerAccount().capacity());
        int lockAfter = loginProperties.lockout().afterFailures();

        List<Integer> statuses = inParallel(2 * capacity, () -> freshClient().login(email, WRONG_PASSWORD).statusCode());

        assertThat(statuses).filteredOn(status -> status == 401).hasSize(capacity);
        assertThat(statuses).filteredOn(status -> status == 429).hasSize(capacity);
        assertThat(countReason(email, "BAD_PASSWORD")).isEqualTo(lockAfter);
        assertThat(countReason(email, "LOCKED")).isEqualTo(capacity - lockAfter);
        assertThat(new LoginApi(new LocalHttp(port)).login(email, STRONG_PASSWORD).statusCode())
                .as("the email's bucket is empty whichever IP asks")
                .isEqualTo(429);
    }

    @Test
    void registerIsLimitedPerIp() {
        RegistrationApi api = new RegistrationApi(new LocalHttp(port), mailpit);
        for (long i = 0; i < limits.registerPerIp().capacity(); i++) {
            assertThat(api.register(uniqueEmail()).statusCode()).isEqualTo(202);
        }

        assertIsRateLimited(api.register(uniqueEmail()));
    }

    @Test
    void verifyEmailIsLimitedPerIp() {
        RegistrationApi api = new RegistrationApi(new LocalHttp(port), mailpit);
        for (long i = 0; i < limits.verifyEmailPerIp().capacity(); i++) {
            assertThat(api.verify(UUID.randomUUID(), "123456").statusCode()).isEqualTo(400);
        }

        assertIsRateLimited(api.verify(UUID.randomUUID(), "123456"));
    }

    @Test
    void resendIsLimitedPerIp() {
        RegistrationApi api = new RegistrationApi(new LocalHttp(port), mailpit);
        for (long i = 0; i < limits.resendPerIp().capacity(); i++) {
            assertThat(api.resend(UUID.randomUUID()).statusCode()).isEqualTo(202);
        }

        assertIsRateLimited(api.resend(UUID.randomUUID()));
    }

    @Test
    void aMalformedRequestStillCostsAToken() {
        LocalHttp http = new LocalHttp(port);
        for (long i = 0; i < limits.loginPerIp().capacity(); i++) {
            assertThat(http.postJson(LoginApi.LOGIN, "{}").statusCode()).isEqualTo(400);
        }

        assertIsRateLimited(http.postJson(LoginApi.LOGIN, "{}"));
    }

    private LoginApi freshClient() {
        return new LoginApi(new LocalHttp(port));
    }

    private long countReason(String email, String reason) {
        Long count = jdbc.queryForObject(
                "select count(*) from login_history where email_attempted = ? and failure_reason = ?",
                Long.class, email, reason);
        return count == null ? 0 : count;
    }

    private static void assertIsRateLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.headers().firstValue("Retry-After"))
                .hasValueSatisfying(seconds -> assertThat(Long.parseLong(seconds)).isPositive());
        assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(429);
        assertThat(JsonPath.<String>read(response.body(), "$.title")).isEqualTo("Too Many Requests");
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("rate_limited");
        assertThat(JsonPath.<String>read(response.body(), "$.correlationId")).isNotBlank();
    }

    private static Map<String, Object> withoutCorrelationId(HttpResponse<String> response) {
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        body.remove("correlationId");
        return body;
    }

    private static List<Integer> inParallel(int count, Supplier<Integer> call) throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            calls.add(call::get);
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
        }
        return statuses;
    }
}
