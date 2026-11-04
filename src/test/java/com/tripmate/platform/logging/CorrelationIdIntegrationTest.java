package com.tripmate.platform.logging;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.List;

import static com.tripmate.support.ProblemScenarioController.BASE;
import static com.tripmate.support.ProblemScenarioController.LEAK_SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdIntegrationTest {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @LocalServerPort
    private int port;

    private LocalHttp http;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
    }

    @Test
    void generatesAnIdWhenTheCallerSendsNone() {
        HttpResponse<String> response = http.get("/actuator/health");

        assertThat(response.headers().firstValue(CorrelationId.HEADER))
                .hasValueSatisfying(id -> assertThat(id).matches(UUID_PATTERN));
    }

    @Test
    void echoesAWellFormedInboundId() {
        HttpResponse<String> response = http.get("/actuator/health", CorrelationId.HEADER, "web-7f3a.retry_2");

        assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue("web-7f3a.retry_2");
    }

    @Test
    void replacesAnInboundIdThatCouldForgeOrFloodLogLines() {
        for (String hostile : List.of("abc\"}{\"level\":\"ERROR", "has spaces", "x".repeat(65))) {
            HttpResponse<String> response = http.get("/actuator/health", CorrelationId.HEADER, hostile);

            assertThat(response.headers().firstValue(CorrelationId.HEADER))
                    .hasValueSatisfying(id -> assertThat(id).matches(UUID_PATTERN));
        }
    }

    @Test
    void requestsRejectedBySecurityStillCarryTheId() {
        HttpResponse<String> response = http.get("/api/v1/trips", CorrelationId.HEADER, "sec-reject-1");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue("sec-reject-1");
    }

    @Test
    void errorDispatchKeepsTheOriginalRequestsId() {
        HttpResponse<String> response = http.get(BASE + "/send-error", CorrelationId.HEADER, "err-dispatch-1");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue(CorrelationId.HEADER)).hasValue("err-dispatch-1");
        assertThat(JsonPath.parse(response.body()).read("$.correlationId", String.class))
                .isEqualTo("err-dispatch-1");
    }

    @Test
    void everyLogLineWrittenDuringARequestIsJsonCarryingItsId(CapturedOutput output) {
        http.get(BASE + "/ok", CorrelationId.HEADER, "log-line-ok-1");
        http.get(BASE + "/unhandled", CorrelationId.HEADER, "log-line-500-1");

        DocumentContext info = singleLogLineContaining(output, "log-line-ok-1");
        assertThat(info.read("$.log.level", String.class)).isEqualTo("INFO");
        assertThat(info.read("$.message", String.class)).isEqualTo("Scenario endpoint handled");
        assertThat(info.read("$.correlationId", String.class)).isEqualTo("log-line-ok-1");

        String errorLine = lineContaining(output, "log-line-500-1");
        DocumentContext error = JsonPath.parse(errorLine);
        assertThat(error.read("$.log.level", String.class)).isEqualTo("ERROR");
        assertThat(error.read("$.correlationId", String.class)).isEqualTo("log-line-500-1");
        assertThat(errorLine).as("full detail stays in the server log").contains(LEAK_SENTINEL);
    }

    private static DocumentContext singleLogLineContaining(CapturedOutput output, String marker) {
        return JsonPath.parse(lineContaining(output, marker));
    }

    private static String lineContaining(CapturedOutput output, String marker) {
        List<String> lines = output.getOut().lines().filter(line -> line.contains(marker)).toList();
        assertThat(lines).as("log lines containing %s", marker).hasSize(1);
        assertThat(lines.getFirst()).startsWith("{").endsWith("}");
        return lines.getFirst();
    }
}
