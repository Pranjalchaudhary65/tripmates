package com.tripmate.platform;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpResponse;
import java.util.Map;

import static com.tripmate.support.ProblemScenarioController.BASE;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class PlatformEndpointsIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private PathMappedEndpoints actuatorEndpoints;

    private LocalHttp http;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
    }

    @Test
    void healthIsPublicAndShowsEachBackingService() {
        HttpResponse<String> response = http.get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.status", String.class)).isEqualTo("UP");
        assertThat(body.read("$.components.db.status", String.class)).isEqualTo("UP");
        assertThat(body.read("$.components.redis.status", String.class)).isEqualTo("UP");
    }

    @Test
    void livenessAndReadinessProbesArePublic() {
        assertThat(http.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(http.get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void healthIsTheOnlyActuatorEndpointThatExists() {
        assertThat(actuatorEndpoints.getAllPaths()).containsExactly("/actuator/health");
    }

    @Test
    void otherActuatorPathsAreClosedToAnonymousCallers() {
        for (String path : new String[] {"/actuator", "/actuator/info", "/actuator/env", "/actuator/heapdump"}) {
            assertThat(http.get(path).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void openApiDocumentIsServedAtItsFixedPathAndCoversOnlyApiV1() {
        HttpResponse<String> response = http.get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.info.title", String.class)).isEqualTo("TripMates API");
        Map<String, Object> paths = body.read("$.paths");
        assertThat(paths).containsKey(BASE + "/unhandled");
        assertThat(paths.keySet()).allMatch(path -> path.startsWith("/api/v1/"));
    }

    @Test
    void swaggerUiIsServedAtItsFixedPath() {
        HttpResponse<String> redirect = http.get("/swagger-ui.html");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/swagger-ui/index.html"));

        HttpResponse<String> page = http.get("/swagger-ui/index.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Swagger UI");
    }

    @Test
    void applicationControllersOnlyAnswerUnderApiV1() {
        assertThat(http.get(BASE + "/ok").statusCode()).isEqualTo(200);
        assertThat(http.get("/test/problems/ok").statusCode()).isEqualTo(401);
    }
}
