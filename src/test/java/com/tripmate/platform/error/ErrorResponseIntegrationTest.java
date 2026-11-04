package com.tripmate.platform.error;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.platform.logging.CorrelationId;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static com.tripmate.support.ProblemScenarioController.BASE;
import static com.tripmate.support.ProblemScenarioController.LEAK_SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ErrorResponseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ErrorResponseIntegrationTest.class);

    @LocalServerPort
    private int port;

    private LocalHttp http;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
    }

    @Test
    void unhandledExceptionBecomesGeneric500WithoutInternals() {
        HttpResponse<String> response = http.get(BASE + "/unhandled");
        log.info("500 response body: {}", response.body());

        DocumentContext body = assertProblem(response, 500);
        assertThat(body.read("$.title", String.class)).isEqualTo("Internal Server Error");
        assertThat(body.read("$.detail", String.class)).startsWith("An unexpected error occurred.");
        assertThat(response.body()).doesNotContain(LEAK_SENTINEL, "IllegalStateException");
    }

    @Test
    void unknownPathUnderAnOpenRouteIs404() {
        HttpResponse<String> response = http.get(BASE + "/does-not-exist");
        log.info("404 response body: {}", response.body());

        DocumentContext body = assertProblem(response, 404);
        assertThat(body.read("$.detail", String.class)).isEqualTo("The requested resource was not found.");
        assertThat(body.read("$.instance", String.class)).isEqualTo(BASE + "/does-not-exist");
    }

    @Test
    void missingStaticResourceIs404ProblemNotFrameworkText() {
        HttpResponse<String> response = http.get("/swagger-ui/does-not-exist.js");

        assertProblem(response, 404);
        assertThat(response.body()).doesNotContainIgnoringCase("static resource");
    }

    @Test
    void anonymousCallToProtectedApiPathIs401WithBearerChallenge() {
        HttpResponse<String> response = http.get("/api/v1/trips");

        assertProblem(response, 401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    }

    @Test
    void accessDeniedInsideAControllerIs403Not500() {
        HttpResponse<String> response = http.get(BASE + "/forbidden");

        assertProblem(response, 403);
        assertThat(response.body()).doesNotContain("ownership check");
    }

    @Test
    void unsupportedMethodIs405() {
        HttpResponse<String> response = http.postJson(BASE + "/unhandled", "{}");

        assertProblem(response, 405);
    }

    @Test
    void sendErrorOutsideMvcIsRenderedByProblemErrorController() {
        HttpResponse<String> response = http.get(BASE + "/send-error");

        DocumentContext body = assertProblem(response, 503);
        assertThat(body.read("$.instance", String.class)).isEqualTo(BASE + "/send-error");
    }

    @Test
    void invalidBodyListsEachFieldWithoutEchoingRejectedValues() {
        HttpResponse<String> response =
                http.postJson(BASE + "/validated", "{\"displayName\":\"\",\"password\":\"hunter2\"}");
        log.info("400 response body: {}", response.body());

        DocumentContext body = assertProblem(response, 400);
        List<Map<String, String>> errors = body.read("$.errors");
        assertThat(errors).extracting(error -> error.get("field"))
                .containsExactlyInAnyOrder("displayName", "password");
        assertThat(response.body()).doesNotContain("hunter2", "scenarioForm", "ScenarioForm");
    }

    @Test
    void invalidRequestParameterIsReportedByParameterName() {
        HttpResponse<String> response = http.get(BASE + "/paged?page=0");

        DocumentContext body = assertProblem(response, 400);
        List<String> fields = body.read("$.errors[*].field");
        assertThat(fields).containsExactly("page");
    }

    @Test
    void malformedJsonIs400WithoutParserDetails() {
        HttpResponse<String> response = http.postJson(BASE + "/validated", "{\"displayName\":");

        assertProblem(response, 400);
        assertThat(response.body()).doesNotContainIgnoringCase("JSON parse");
    }

    /**
     * Asserts the shape every error response must share, and that the body
     * names no Java package, class or stack frame.
     */
    private static DocumentContext assertProblem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));

        String correlationHeader = response.headers().firstValue(CorrelationId.HEADER).orElseThrow();
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.status", Integer.class)).isEqualTo(status);
        assertThat(body.read("$.title", String.class)).isNotBlank();
        assertThat(body.read("$.detail", String.class)).isNotBlank();
        assertThat(body.read("$.correlationId", String.class)).isEqualTo(correlationHeader);
        assertThat(response.body()).doesNotContain(
                "Exception", "org.springframework", "java.", "jakarta.", "com.tripmate", "\tat ", "trace");
        return body;
    }
}
