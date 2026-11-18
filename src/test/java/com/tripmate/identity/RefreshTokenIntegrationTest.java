package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.service.DeviceIds;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class RefreshTokenIntegrationTest {

    /** Just past tripmate.identity.session.reuse-grace. */
    private static final Duration PAST_GRACE = Duration.ofSeconds(11);

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private LocalHttp http;

    private SessionApi api;

    private String email;

    private SessionApi.Session session;

    @BeforeEach
    void signIn() {
        http = new LocalHttp(port);
        api = new SessionApi(http);
        email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        session = api.signIn(email, STRONG_PASSWORD);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void refreshRotatesTheCookieAndReturnsAWorkingAccessToken() {
        HttpResponse<String> response = api.refresh(session.refreshToken());

        assertThat(response.statusCode()).isEqualTo(200);
        String rotated = SessionApi.refreshToken(response);
        assertThat(rotated).isNotEqualTo(session.refreshToken()).startsWith(session.sessionId() + ".");
        assertThat(SessionApi.refreshSetCookie(response)).hasValueSatisfying(cookie -> assertThat(cookie)
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth/refresh"));
        assertThat(response.body()).doesNotContain(rotated);
        assertThat(JsonPath.<String>read(response.body(), "$.user.id")).isEqualTo(session.userId());
        assertThat(JsonPath.<Integer>read(response.body(), "$.expiresIn")).isEqualTo(900);

        String accessToken = SessionApi.accessTokenOfRefresh(response);
        assertThat(accessToken).isNotEqualTo(session.accessToken());
        assertThat(api.me(accessToken).statusCode()).isEqualTo(200);

        Map<Object, Object> state = redis.opsForHash().entries("refresh:" + session.sessionId());
        assertThat(state.get("current")).isEqualTo(DeviceIds.hash(rotated));
        assertThat(state.get("previous")).isEqualTo(DeviceIds.hash(session.refreshToken()));
        assertThat(state.values()).doesNotContain(rotated, session.refreshToken());
    }

    @Test
    void theCookieNeverOutlivesTheSessionsFixedLifetime() {
        clock.advance(Duration.ofDays(10));

        HttpResponse<String> response = api.refresh(session.refreshToken());

        assertThat(SessionApi.refreshSetCookie(response)).hasValueSatisfying(cookie -> {
            long maxAge = Long.parseLong(cookie.replaceAll(".*Max-Age=(\\d+).*", "$1"));
            assertThat(maxAge).isBetween(Duration.ofDays(20).toSeconds() - 60, Duration.ofDays(20).toSeconds());
        });
    }

    @Test
    void aThiefReplayingARotatedTokenEndsTheSessionForEveryone() {
        HttpResponse<String> owner = api.refresh(session.refreshToken());
        String ownersToken = SessionApi.refreshToken(owner);
        String ownersAccessToken = SessionApi.accessTokenOfRefresh(owner);
        clock.advance(PAST_GRACE);

        HttpResponse<String> replay = api.refresh(session.refreshToken());

        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(replay)).isEqualTo("invalid_refresh_token");
        assertThat(SessionApi.clearsRefreshCookie(replay)).isTrue();
        assertThat(api.refresh(ownersToken).statusCode()).isEqualTo(401);
        assertThat(api.me(ownersAccessToken).statusCode()).isEqualTo(401);
        assertThat(api.me(session.accessToken()).statusCode()).isEqualTo(401);
        assertThat(revocation(session.sessionId())).isEqualTo("REUSE_DETECTED");
        assertThat(redis.hasKey("refresh:" + session.sessionId())).isFalse();
    }

    @Test
    void reuseEndsOnlyTheFamilyItHappenedInNotTheUsersOtherSessions() {
        SessionApi.Session otherDevice = api.signIn(email, STRONG_PASSWORD);
        api.refresh(session.refreshToken());
        clock.advance(PAST_GRACE);

        assertThat(api.refresh(session.refreshToken()).statusCode()).isEqualTo(401);

        assertThat(api.me(otherDevice.accessToken()).statusCode()).isEqualTo(200);
        assertThat(api.refresh(otherDevice.refreshToken()).statusCode()).isEqualTo(200);
        assertThat(revocation(otherDevice.sessionId())).isNull();
    }

    @Test
    void twoTabsRefreshingWithTheSameCookieDoNotSignTheUserOut() {
        HttpResponse<String> firstTab = api.refresh(session.refreshToken());
        HttpResponse<String> secondTab = api.refresh(session.refreshToken());

        assertThat(firstTab.statusCode()).isEqualTo(200);
        assertThat(secondTab.statusCode()).isEqualTo(200);
        assertThat(SessionApi.refreshSetCookie(secondTab))
                .as("the second tab must not overwrite the cookie the first tab just received").isEmpty();
        assertThat(api.me(SessionApi.accessTokenOfRefresh(secondTab)).statusCode()).isEqualTo(200);

        HttpResponse<String> next = api.refresh(SessionApi.refreshToken(firstTab));
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(revocation(session.sessionId())).isNull();
    }

    @Test
    void parallelRefreshesWithOneTokenLeaveExactlyOneLiveToken() throws Exception {
        int callers = 12;
        List<HttpResponse<String>> responses = concurrently(callers, () -> api.refresh(session.refreshToken()));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        List<String> issued = responses.stream()
                .filter(response -> SessionApi.refreshSetCookie(response).isPresent())
                .map(SessionApi::refreshToken)
                .toList();
        assertThat(issued).hasSize(1);
        String live = issued.getFirst();
        assertThat(redis.opsForHash().get("refresh:" + session.sessionId(), "current"))
                .isEqualTo(DeviceIds.hash(live));

        clock.advance(PAST_GRACE);
        HttpResponse<String> next = api.refresh(live);
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(SessionApi.refreshToken(next)).isNotEqualTo(live);
    }

    @Test
    void parallelReplaysOfAStolenTokenCannotMintAFreshRefreshToken() throws Exception {
        api.refresh(session.refreshToken());
        clock.advance(PAST_GRACE);

        List<HttpResponse<String>> responses = concurrently(8, () -> api.refresh(session.refreshToken()));

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(401);
        assertThat(responses).noneMatch(response ->
                SessionApi.refreshSetCookie(response).filter(cookie -> !cookie.startsWith("tm_refresh=;")).isPresent());
        assertThat(revocation(session.sessionId())).isEqualTo("REUSE_DETECTED");
    }

    @Test
    void refreshWithoutTheCsrfHeaderIsRefusedAndLeavesTheCookieAlone() {
        HttpResponse<String> missing = api.refreshWithoutCsrfHeader(session.refreshToken());
        HttpResponse<String> wrongValue = http.post(SessionApi.REFRESH,
                "Cookie", SessionApi.REFRESH_COOKIE + "=" + session.refreshToken(), SessionApi.CSRF_HEADER, "0");

        for (HttpResponse<String> response : List.of(missing, wrongValue)) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(SessionApi.code(response)).isEqualTo("csrf_rejected");
            assertThat(SessionApi.refreshSetCookie(response)).isEmpty();
        }
        assertThat(api.refresh(session.refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void refreshFromAnOriginOutsideTheAllowlistIsRefusedEvenWithTheHeader() {
        HttpResponse<String> response = api.refresh(session.refreshToken(), "Origin", "https://evil.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(SessionApi.refreshSetCookie(response)).isEmpty();
        assertThat(api.refresh(session.refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void theAppsOriginMayRefreshAndReadTheResult() {
        HttpResponse<String> preflight = http.options(SessionApi.REFRESH,
                "Origin", ALLOWED_ORIGIN,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "x-tripmates-csrf");
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers"))
                .hasValueSatisfying(allowed -> assertThat(allowed.toLowerCase()).contains("x-tripmates-csrf"));

        HttpResponse<String> response = api.refresh(session.refreshToken(), "Origin", ALLOWED_ORIGIN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ALLOWED_ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
    }

    @Test
    void missingMalformedAndMadeUpCookiesAllGetTheSameAnswer() {
        List<HttpResponse<String>> responses = List.of(
                http.post(SessionApi.REFRESH, SessionApi.CSRF_HEADER, "1"),
                api.refresh("not-a-refresh-token"),
                api.refresh(UUID.randomUUID() + "." + DeviceIds.generate()),
                api.refresh(session.sessionId() + "." + DeviceIds.generate()));

        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(SessionApi.code(response)).isEqualTo("invalid_refresh_token");
        }
    }

    @Test
    void aSessionCannotBeRefreshedPastItsFixedLifetime() {
        clock.advance(Duration.ofDays(30).plusMinutes(1));

        assertThat(api.refresh(session.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void aStaleAccessTokenSentAlongIsIgnored() {
        HttpResponse<String> response = api.refresh(session.refreshToken(), "Authorization", "Bearer expired.or.junk");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void refreshRecordsWhenAndFromWhereTheSessionWasLastUsed() {
        LocalHttp elsewhere = new LocalHttp(port, "203.0.113.77");
        clock.advance(Duration.ofMinutes(20));

        new SessionApi(elsewhere).refresh(session.refreshToken());

        Map<String, Object> row = jdbc.queryForMap("""
                select host(last_ip_address) as ip, last_used_at > created_at + interval '19 minutes' as moved
                from refresh_token_families where id = ?::uuid""", session.sessionId());
        assertThat(row.get("ip")).isEqualTo("203.0.113.77");
        assertThat(row.get("moved")).isEqualTo(true);
    }

    private String revocation(String sessionId) {
        return jdbc.queryForObject("select revoked_reason from refresh_token_families where id = ?::uuid",
                String.class, sessionId);
    }

    private static List<HttpResponse<String>> concurrently(int callers, Callable<HttpResponse<String>> call)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }
}
