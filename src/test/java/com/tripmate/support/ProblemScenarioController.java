package com.tripmate.support;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Test-only endpoints that fail in each way the error handling has to cover.
 * {@code @TestComponent} keeps it out of component scanning, so it exists
 * only where {@link IntegrationTest} imports it. It still sits under
 * com.tripmate, so it receives the /api/v1 prefix like a real controller.
 */
@TestComponent
@RestController
@RequestMapping("/test/problems")
public class ProblemScenarioController {

    public static final String BASE = "/api/v1/test/problems";

    /** Looks like the internals a careless handler would leak; must never appear in a response. */
    public static final String LEAK_SENTINEL =
            "connection to db-internal.tripmate.local:5432 refused for role tripmate_app";

    private static final Logger log = LoggerFactory.getLogger(ProblemScenarioController.class);

    @GetMapping("/ok")
    Map<String, String> ok() {
        log.info("Scenario endpoint handled");
        return Map.of("status", "ok");
    }

    @GetMapping("/unhandled")
    Map<String, String> unhandled() {
        throw new IllegalStateException(LEAK_SENTINEL);
    }

    @GetMapping("/send-error")
    void sendError(HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @GetMapping("/forbidden")
    Map<String, String> forbidden() {
        throw new AccessDeniedException("Denied by an ownership check");
    }

    @PostMapping("/validated")
    ResponseEntity<Void> validated(@Valid @RequestBody ScenarioForm form) {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/paged")
    Map<String, Integer> paged(@RequestParam @Min(1) int page) {
        return Map.of("page", page);
    }

    public record ScenarioForm(@NotBlank String displayName, @Size(min = 12) String password) {
    }
}
