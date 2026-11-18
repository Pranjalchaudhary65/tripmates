package com.tripmate.identity;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SessionManagementIntegrationTest {

    private static final String FIREFOX_LINUX =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private LocalHttp http;

    private SessionApi api;

    private String email;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
        api = new SessionApi(http);
        email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
    }

    @Test
    void logoutEndsThisSessionAtOnceAndLeavesTheOthers() {
        SessionApi.Session laptop = api.signIn(email, STRONG_PASSWORD);
        SessionApi.Session phone = api.signIn(email, STRONG_PASSWORD);

        HttpResponse<String> response = api.logout(laptop.accessToken());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(SessionApi.clearsRefreshCookie(response)).isTrue();
        assertThat(SessionApi.refreshSetCookie(response)).hasValueSatisfying(cookie ->
                assertThat(cookie).contains("Path=/api/v1/auth/refresh"));
        // The access token had up to 15 minutes left; it is refused now, not then.
        assertThat(api.me(laptop.accessToken()).statusCode()).isEqualTo(401);
        assertThat(api.refresh(laptop.refreshToken()).statusCode()).isEqualTo(401);
        assertThat(api.logout(laptop.accessToken()).statusCode()).isEqualTo(401);
        assertThat(revocation(laptop.sessionId())).isEqualTo("LOGOUT");

        assertThat(api.me(phone.accessToken()).statusCode()).isEqualTo(200);
        assertThat(api.refresh(phone.refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void revokeAllEndsEverySessionOfTheCallerAndNoOneElses() {
        SessionApi.Session laptop = api.signIn(email, STRONG_PASSWORD);
        SessionApi.Session phone = api.signIn(email, STRONG_PASSWORD);
        String someoneElse = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(someoneElse);
        SessionApi.Session stranger = api.signIn(someoneElse, STRONG_PASSWORD);

        HttpResponse<String> response = api.revokeAll(phone.accessToken());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(SessionApi.clearsRefreshCookie(response)).isTrue();
        for (SessionApi.Session ended : List.of(laptop, phone)) {
            assertThat(api.me(ended.accessToken()).statusCode()).isEqualTo(401);
            assertThat(api.refresh(ended.refreshToken()).statusCode()).isEqualTo(401);
            assertThat(revocation(ended.sessionId())).isEqualTo("REVOKE_ALL_DEVICES");
        }
        assertThat(api.me(stranger.accessToken()).statusCode()).isEqualTo(200);
        assertThat(revocation(stranger.sessionId())).isNull();
    }

    @Test
    void theSessionsListShowsOnlyTheCallersLiveSessionsAndMarksTheCurrentOne() {
        SessionApi laptopClient = new SessionApi(new LocalHttp(port, "203.0.113.30"));
        SessionApi.Session laptop = laptopClient.signIn(email, STRONG_PASSWORD, "User-Agent", FIREFOX_LINUX);
        SessionApi.Session phone = api.signIn(email, STRONG_PASSWORD);
        SessionApi.Session ended = api.signIn(email, STRONG_PASSWORD);
        api.logout(ended.accessToken());
        String someoneElse = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(someoneElse);
        SessionApi.Session stranger = api.signIn(someoneElse, STRONG_PASSWORD);

        HttpResponse<String> response = laptopClient.sessions(laptop.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        List<String> ids = body.read("$.items[*].id");
        assertThat(ids).containsExactlyInAnyOrder(laptop.sessionId(), phone.sessionId())
                .doesNotContain(ended.sessionId(), stranger.sessionId());
        List<String> current = body.read("$.items[?(@.current == true)].id");
        assertThat(current).containsExactly(laptop.sessionId());

        Map<String, Object> laptopRow = body.<List<Map<String, Object>>>read(
                "$.items[?(@.id == '" + laptop.sessionId() + "')]").getFirst();
        assertThat(laptopRow.get("device")).isEqualTo("Firefox on Linux");
        assertThat(laptopRow.get("ipAddress")).isEqualTo("203.0.113.30");
        assertThat(laptopRow).containsKeys("signedInAt", "lastActiveAt", "expiresAt")
                .doesNotContainKeys("userAgent", "userId", "refreshToken");

        List<String> fromPhone = JsonPath.read(api.sessions(phone.accessToken()).body(),
                "$.items[?(@.current == true)].id");
        assertThat(fromPhone).containsExactly(phone.sessionId());
    }

    @Test
    void aHostileUserAgentIsStoredButNeverEchoedBack() {
        String hostile = "<script>alert(1)</script> X-Injected: yes Mozilla/5.0 (Windows NT 10.0) Chrome/120.0";
        SessionApi.Session session = api.signIn(email, STRONG_PASSWORD, "User-Agent", hostile);

        HttpResponse<String> response = api.sessions(session.accessToken());

        assertThat(JsonPath.<List<String>>read(response.body(), "$.items[*].device")).containsExactly("Chrome on Windows");
        assertThat(response.body()).doesNotContain("script", "alert", "Injected");
        assertThat(jdbc.queryForObject("select user_agent from refresh_token_families where id = ?::uuid",
                String.class, session.sessionId())).contains("<script>");
    }

    @Test
    void everySessionRouteRefusesAnonymousCallers() {
        for (HttpResponse<String> response : List.of(
                http.get(SessionApi.SESSIONS),
                http.post(SessionApi.LOGOUT),
                http.post(SessionApi.REVOKE_ALL),
                http.get(SessionApi.ME))) {
            assertThat(response.statusCode()).as(response.uri().toString()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        }
    }

    @Test
    void theRefreshCookieAloneAuthenticatesNothingButRefresh() {
        SessionApi.Session session = api.signIn(email, STRONG_PASSWORD);
        String cookie = SessionApi.REFRESH_COOKIE + "=" + session.refreshToken();

        assertThat(http.get(SessionApi.SESSIONS, "Cookie", cookie).statusCode()).isEqualTo(401);
        assertThat(http.post(SessionApi.LOGOUT, "Cookie", cookie, SessionApi.CSRF_HEADER, "1").statusCode())
                .isEqualTo(401);
        assertThat(revocation(session.sessionId())).isNull();
    }

    @Test
    void everySessionRouteIsDocumentedWithProblemErrorsAndTheBearerScheme() {
        DocumentContext doc = JsonPath.parse(http.get("/v3/api-docs").body());

        assertThat(doc.<String>read("$.components.securitySchemes.bearerAuth.type")).isEqualTo("http");
        assertThat(doc.<String>read("$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
        Map<String, String> protectedRoutes = Map.of(
                SessionApi.LOGOUT, "post", SessionApi.REVOKE_ALL, "post",
                SessionApi.SESSIONS, "get", SessionApi.ME, "get");
        protectedRoutes.forEach((path, method) -> {
            String operation = "$.paths['" + path + "']." + method;
            assertThat(doc.<List<Map<String, Object>>>read(operation + ".security")).as(path)
                    .anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
            assertThat(doc.<Map<String, Object>>read(operation + ".responses['401'].content")).as(path)
                    .containsKey("application/problem+json");
        });

        String refresh = "$.paths['" + SessionApi.REFRESH + "'].post";
        assertThat(doc.<Map<String, Object>>read(refresh)).doesNotContainKey("security");
        assertThat(doc.<List<String>>read(refresh + ".parameters[?(@.in == 'header')].name"))
                .containsExactly(SessionApi.CSRF_HEADER);
        assertThat(doc.<Map<String, Object>>read(refresh + ".responses")).containsKeys("200", "401", "403", "429", "503");
        assertThat(doc.<Map<String, Object>>read(refresh + ".responses['403'].content"))
                .containsKey("application/problem+json");
        assertThat(doc.<Map<String, Object>>read("$.paths['" + LoginApi.LOGIN + "'].post")).doesNotContainKey("security");
    }

    private String revocation(String sessionId) {
        return jdbc.queryForObject("select revoked_reason from refresh_token_families where id = ?::uuid",
                String.class, sessionId);
    }
}
