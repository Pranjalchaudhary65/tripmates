package com.tripmate.platform.security;

import com.tripmate.platform.logging.CorrelationId;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static com.tripmate.support.ProblemScenarioController.BASE;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class CorsIntegrationTest {

    /** Matches tripmate.cors.allowed-origins in application-test.yml. */
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private static final String EVIL_ORIGIN = "https://evil.example";

    private static final String STRICT_CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @LocalServerPort
    private int port;

    private LocalHttp http;

    private final List<HttpResponse<String>> responses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
    }

    /** Whatever a test asserted, none of its responses may carry a wildcard origin. */
    @AfterEach
    void noResponseEverAllowsAnyOrigin() {
        assertThat(responses).isNotEmpty();
        for (HttpResponse<String> response : responses) {
            assertThat(response.headers().allValues("Access-Control-Allow-Origin"))
                    .as(response.request().method() + " " + response.uri())
                    .doesNotContain("*");
        }
    }

    @Test
    void preflightFromAllowedOriginIsAnsweredBeforeAuthentication() {
        HttpResponse<String> response = preflight("/api/v1/trips", ALLOWED_ORIGIN, "POST",
                "authorization, content-type, x-correlation-id");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ALLOWED_ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods"))
                .hasValue("GET,POST,PUT,PATCH,DELETE");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers")).hasValueSatisfying(
                allowed -> assertThat(allowed.toLowerCase())
                        .contains("authorization", "content-type", "x-correlation-id"));
        assertThat(response.headers().firstValue("Access-Control-Max-Age")).hasValue("3600");
        assertThat(response.headers().allValues("Vary")).anySatisfy(vary -> assertThat(vary).contains("Origin"));
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(STRICT_CSP);
    }

    @Test
    void preflightFromUnknownOriginIsRejectedWithProblemBody() {
        HttpResponse<String> response = preflight("/api/v1/trips", EVIL_ORIGIN, "POST", "content-type");

        assertRejected(response);
    }

    @Test
    void originsThatOnlyResembleTheAllowedOneAreRejected() {
        for (String origin : List.of("http://localhost:5173.evil.example", "http://localhost:51730",
                "https://localhost:5173", "http://127.0.0.1:5173", "null")) {
            HttpResponse<String> response = preflight("/api/v1/trips", origin, "GET", "authorization");
            assertThat(response.statusCode()).as(origin).isEqualTo(403);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).as(origin).isEmpty();
        }
    }

    @Test
    void preflightAskingForAnUnlistedHeaderIsRejected() {
        assertRejected(preflight("/api/v1/trips", ALLOWED_ORIGIN, "POST", "content-type, x-api-key"));
    }

    @Test
    void preflightAskingForAnUnlistedMethodIsRejected() {
        assertRejected(preflight("/api/v1/trips", ALLOWED_ORIGIN, "TRACE", "content-type"));
    }

    @Test
    void credentialedRequestFromAllowedOriginCanReadTheCorrelationId() {
        HttpResponse<String> response = record(http.get(BASE + "/ok", "Origin", ALLOWED_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ALLOWED_ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers")).hasValue(CorrelationId.HEADER);
        assertThat(response.headers().firstValue(CorrelationId.HEADER)).isPresent();
    }

    /** The React app's refresh interceptor can only see a 401 if the 401 is CORS-readable. */
    @Test
    void unauthenticatedResponseIsReadableByTheAllowedOrigin() {
        HttpResponse<String> response = record(http.get("/api/v1/trips", "Origin", ALLOWED_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ALLOWED_ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");
    }

    @Test
    void simpleRequestFromUnknownOriginIsRejectedBeforeReachingTheController() {
        HttpResponse<String> response = record(http.get(BASE + "/ok", "Origin", EVIL_ORIGIN));

        assertRejected(response);
        assertThat(response.body()).doesNotContain("\"status\":\"ok\"");
    }

    @Test
    void malformedOriginIsRejected() {
        HttpResponse<String> response = record(http.get(BASE + "/ok", "Origin", "http://[not-an-origin"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void sameOriginAndNonBrowserCallsAreUnaffected() {
        HttpResponse<String> response = record(http.get(BASE + "/ok"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void pathsOutsideTheApiGetNoCorsHeaders() {
        HttpResponse<String> response = record(http.get("/actuator/health", "Origin", ALLOWED_ORIGIN));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String path, String origin, String method, String requestHeaders) {
        return record(http.options(path,
                "Origin", origin,
                "Access-Control-Request-Method", method,
                "Access-Control-Request-Headers", requestHeaders));
    }

    private HttpResponse<String> record(HttpResponse<String> response) {
        responses.add(response);
        return response;
    }

    private static void assertRejected(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(response.headers().firstValue(CorrelationId.HEADER)).isPresent();
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(STRICT_CSP);
        assertThat(response.body()).doesNotContain("Invalid CORS request");
    }
}
