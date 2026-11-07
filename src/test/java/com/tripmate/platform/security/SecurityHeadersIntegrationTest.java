package com.tripmate.platform.security;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tripmate.support.ProblemScenarioController.BASE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policies are asserted as literal strings rather than through the
 * constants in {@link SecurityHeaders}, so loosening a constant fails here
 * instead of silently moving the expectation with it.
 */
@IntegrationTest
class SecurityHeadersIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SecurityHeadersIntegrationTest.class);

    private static final String STRICT_CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final String LOGO_STYLE_HASH = "'sha256-RL3ie0nH+Lzz2YNqQN83mnU0J1ot4QL7b99vMdIX99w='";

    private static final String SWAGGER_UI_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "style-src-elem 'self' " + LOGO_STYLE_HASH + "; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final String HASH_OUTDATED =
            "springdoc upgrade changed the injected logo CSS, update the hash in SecurityHeaders";

    private static final Pattern ASSET_REFERENCE = Pattern.compile("(?:src|href)=\"([^\"]+)\"");

    private static final Pattern SCRIPT_REFERENCE = Pattern.compile("<script[^>]*\\bsrc=\"([^\"]+)\"");

    private static final Pattern STYLE_ELEMENT = Pattern.compile("createElement\\(\"style\"");

    private static final Pattern LITERAL_STYLE_ELEMENT =
            Pattern.compile("createElement\\(\"style\",null,\"((?:[^\"\\\\]|\\\\.)*)\"\\)");

    @LocalServerPort
    private int port;

    private LocalHttp http;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
    }

    @Test
    void successfulResponseCarriesStrictHeaders() {
        HttpResponse<String> response = http.get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertHardened(response, STRICT_CSP);
    }

    @Test
    void unauthenticatedResponseCarriesStrictHeaders() {
        HttpResponse<String> response = http.get("/api/v1/trips");

        assertThat(response.statusCode()).isEqualTo(401);
        assertHardened(response, STRICT_CSP);
    }

    @Test
    void forbiddenResponseCarriesStrictHeaders() {
        HttpResponse<String> response = http.get(BASE + "/forbidden");

        assertThat(response.statusCode()).isEqualTo(403);
        assertHardened(response, STRICT_CSP);
    }

    @Test
    void notFoundResponseCarriesStrictHeaders() {
        HttpResponse<String> response = http.get(BASE + "/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertHardened(response, STRICT_CSP);
    }

    @Test
    void errorsRenderedThroughTheErrorDispatchKeepTheirHeaders() {
        HttpResponse<String> unhandled = http.get(BASE + "/unhandled");
        HttpResponse<String> sendError = http.get(BASE + "/send-error");

        assertThat(unhandled.statusCode()).isEqualTo(500);
        assertHardened(unhandled, STRICT_CSP);
        assertThat(sendError.statusCode()).isEqualTo(503);
        assertHardened(sendError, STRICT_CSP);
    }

    @Test
    void openApiDocumentAndSwaggerRedirectKeepTheStrictPolicy() {
        HttpResponse<String> apiDocs = http.get("/v3/api-docs");
        HttpResponse<String> redirect = http.get("/swagger-ui.html");

        assertThat(apiDocs.statusCode()).isEqualTo(200);
        assertHardened(apiDocs, STRICT_CSP);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertHardened(redirect, STRICT_CSP);
    }

    @Test
    void swaggerUiLookalikePathsDoNotGetTheLooserPolicy() {
        for (String path : List.of("/api/v1/swagger-ui/index.html", "/swagger-uix/index.html", "/swagger-ui.css")) {
            HttpResponse<String> response = http.get(path);
            assertThat(cspOf(response)).as(path).isEqualTo(STRICT_CSP);
        }
    }

    @Test
    void swaggerUiPageGetsItsOwnPolicyAndEveryOtherHeader() {
        HttpResponse<String> page = http.get("/swagger-ui/index.html");
        log.info("Swagger UI CSP: {}", cspOf(page));

        assertThat(page.statusCode()).isEqualTo(200);
        assertHardened(page, SWAGGER_UI_CSP);
    }

    @Test
    void missingSwaggerAssetIs404WithTheSwaggerPolicy() {
        HttpResponse<String> response = http.get("/swagger-ui/does-not-exist.js");

        assertThat(response.statusCode()).isEqualTo(404);
        assertHardened(response, SWAGGER_UI_CSP);
    }

    /**
     * The only relaxation anywhere is one hashed style element on Swagger UI
     * paths. Checked per directive as well as by the literal comparisons
     * above, so the intent survives a reordering of the policy string.
     */
    @Test
    void onlySwaggerUiLogoStyleElementIsRelaxed() {
        String swagger = cspOf(http.get("/swagger-ui/index.html"));
        String api = cspOf(http.get("/actuator/health"));

        assertThat(directive(swagger, "script-src")).isEqualTo("script-src 'self'");
        assertThat(directive(swagger, "style-src")).isEqualTo("style-src 'self'");
        assertThat(directive(swagger, "style-src-elem")).isEqualTo("style-src-elem 'self' " + LOGO_STYLE_HASH);
        assertThat(directive(swagger, "style-src-attr")).isNull();
        assertThat(swagger).doesNotContain("unsafe");
        assertThat(api).doesNotContain("unsafe", "style-src", "script-src", "sha256-");
    }

    /**
     * Re-derives the style-src-elem hash from what the server actually ships.
     * Every script the Swagger UI page loads is scanned for React-rendered
     * {@code <style>} elements; each must be a plain string literal (so its
     * runtime text is exactly the literal) and its SHA-256 must appear in the
     * served policy. A springdoc upgrade that changes the logo CSS, or adds a
     * new inline style element, fails here instead of in a browser console.
     */
    @Test
    void everyInlineStyleElementSwaggerUiRendersIsCoveredByAHash() {
        HttpResponse<String> page = http.get("/swagger-ui/index.html");
        String elementSources = directive(cspOf(page), "style-src-elem");
        assertThat(elementSources).isNotNull();

        Matcher scripts = SCRIPT_REFERENCE.matcher(page.body());
        List<String> hashes = new ArrayList<>();
        while (scripts.find()) {
            String path = "/swagger-ui/" + scripts.group(1).replaceFirst("^\\./", "");
            String script = http.get(path).body();

            long styleElements = STYLE_ELEMENT.matcher(script).results().count();
            List<String> literals = LITERAL_STYLE_ELEMENT.matcher(script).results().map(m -> m.group(1)).toList();
            assertThat(literals)
                    .as(HASH_OUTDATED + " - %s renders a <style> whose CSS is not a plain string literal; "
                            + "read it from the page in the browser and hash that", path)
                    .hasSize((int) styleElements);
            for (String css : literals) {
                assertThat(css)
                        .as(HASH_OUTDATED + " - %s has escape sequences in its <style> CSS, so the literal is not "
                                + "the runtime text; read it from the page in the browser and hash that", path)
                        .doesNotContain("\\");
                hashes.add("'sha256-" + sha256Base64(css) + "'");
            }
        }

        assertThat(hashes).as(HASH_OUTDATED + " - no inline <style> found in Swagger UI's scripts").isNotEmpty();
        for (String hash : hashes) {
            assertThat(elementSources).as(HASH_OUTDATED + " - served CSS now hashes to " + hash).contains(hash);
        }
        assertThat(hashes).as(HASH_OUTDATED + " - the policy allows a hash nothing renders any more")
                .containsExactly(LOGO_STYLE_HASH);
    }

    /**
     * Scripts are 'self' only, so the page itself must carry no inline
     * script, inline style or event-handler attribute, and every asset it
     * references must be served from this origin.
     */
    @Test
    void swaggerUiPageWorksUnderItsPolicy() {
        String html = http.get("/swagger-ui/index.html").body();

        assertThat(html).doesNotContainPattern("(?i)<script(?![^>]*\\bsrc=)[^>]*>");
        assertThat(html).doesNotContainPattern("(?i)<style");
        assertThat(html).doesNotContainPattern("(?i)\\sstyle=");
        assertThat(html).doesNotContainPattern("(?i)\\son[a-z]+=");

        Matcher assets = ASSET_REFERENCE.matcher(html);
        int assetCount = 0;
        while (assets.find()) {
            String reference = assets.group(1);
            assertThat(reference).as("asset must be same-origin").doesNotContain("://").doesNotStartWith("//");
            String path = "/swagger-ui/" + reference.replaceFirst("^\\./", "");
            HttpResponse<String> asset = http.get(path);
            assertThat(asset.statusCode()).as(path).isEqualTo(200);
            assertThat(cspOf(asset)).as(path).isEqualTo(SWAGGER_UI_CSP);
            assetCount++;
        }
        assertThat(assetCount).isGreaterThanOrEqualTo(5);
    }

    /**
     * springdoc leaves Swagger UI's sample petstore URL in the initializer
     * and appends a configUrl; the config served there replaces the sample
     * with this app's own document, so nothing is fetched cross-origin.
     * useUnsafeMarkdown must stay off: it is what makes Swagger UI strip
     * style attributes from rendered markdown, which is why the policy can
     * leave style attributes blocked.
     */
    @Test
    void swaggerUiIsConfiguredFromThisOriginWithTheValidatorDisabled() {
        String initializer = http.get("/swagger-ui/swagger-initializer.js").body();
        assertThat(initializer).contains("\"configUrl\" : \"/v3/api-docs/swagger-config\"");

        HttpResponse<String> config = http.get("/v3/api-docs/swagger-config");
        assertThat(config.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(config.body());
        assertThat(body.read("$.url", String.class)).isEqualTo("/v3/api-docs");
        assertThat(body.read("$.validatorUrl", String.class)).isEqualTo("none");
        Map<String, Object> settings = body.read("$");
        assertThat(settings).doesNotContainEntry("useUnsafeMarkdown", true);
    }

    private static void assertHardened(HttpResponse<String> response, String expectedCsp) {
        HttpHeaders headers = response.headers();
        assertThat(headers.allValues("Content-Security-Policy")).containsExactly(expectedCsp);
        assertThat(headers.firstValue("Strict-Transport-Security")).hasValue("max-age=31536000 ; includeSubDomains");
        assertThat(headers.firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(headers.firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(headers.firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(headers.firstValue("Permissions-Policy")).hasValue(
                "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
                        + "magnetometer=(), microphone=(), payment=(), usb=()");
    }

    private static String cspOf(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Security-Policy").orElse(null);
    }

    private static String sha256Base64(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    private static String directive(String policy, String name) {
        for (String directive : policy.split(";")) {
            String trimmed = directive.strip();
            if (trimmed.equals(name) || trimmed.startsWith(name + " ")) {
                return trimmed;
            }
        }
        return null;
    }
}
