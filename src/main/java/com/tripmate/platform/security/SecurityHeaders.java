package com.tripmate.platform.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.time.Duration;

/**
 * Browser-facing response headers, written by Spring Security's
 * HeaderWriterFilter on every response its chain handles - 401, 403 and 404
 * included, because the filter writes them when the response is committed,
 * not when a controller returns.
 *
 * <p>The API only ever returns JSON, so its Content-Security-Policy allows
 * nothing at all: if a response is ever rendered as a page (a reflected value
 * sniffed as HTML, a browser opening a URL directly), no script, style, frame
 * or form can run from it. Swagger UI is the one HTML page the server ships
 * and gets a policy of its own, matched on {@code /swagger-ui/**} only;
 * every other path falls through to the strict one.
 */
public final class SecurityHeaders {

    static final String API_CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * SHA-256 of the one inline {@code <style>} element Swagger UI renders:
     * the CSS inside the top-bar logo SVG, defined in
     * swagger-ui-standalone-preset.js. The CSS is a fixed literal in the
     * webjar, not derived from the API spec, so a hash covers it exactly. It
     * changes only when springdoc ships a different Swagger UI;
     * SecurityHeadersIntegrationTest re-hashes the served script and fails
     * when that happens.
     */
    static final String SWAGGER_UI_LOGO_STYLE_HASH = "'sha256-RL3ie0nH+Lzz2YNqQN83mnU0J1ot4QL7b99vMdIX99w='";

    /**
     * Swagger UI loads its scripts, stylesheets and favicons from its own
     * webjar, embeds icons as data: URIs in its CSS, and calls the API on the
     * same origin. Scripts stay 'self' only - no 'unsafe-inline', no
     * 'unsafe-eval'.
     *
     * <p>The one relaxation is the logo's {@code <style>} element, allowed by
     * hash in {@code style-src-elem}, so no other inline stylesheet can run -
     * injected CSS could otherwise use attribute selectors to leak page
     * content. Style attributes stay blocked: {@code style-src-attr} is not
     * set, so they fall back to {@code style-src 'self'}, which no attribute
     * can satisfy. Swagger UI needs none - its markdown renderers strip
     * {@code style} attributes with DOMPurify, and React applies its own
     * style props through the CSSOM, which CSP does not govern.
     */
    static final String SWAGGER_UI_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "style-src-elem 'self' " + SWAGGER_UI_LOGO_STYLE_HASH + "; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
            + "magnetometer=(), microphone=(), payment=(), usb=()";

    static final long HSTS_MAX_AGE_SECONDS = Duration.ofDays(365).toSeconds();

    private static final RequestMatcher SWAGGER_UI = PathPatternRequestMatcher.pathPattern("/swagger-ui/**");

    private SecurityHeaders() {
    }

    public static Customizer<HeadersConfigurer<HttpSecurity>> policy() {
        return headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                // Spring only sends HSTS on requests it sees as HTTPS. Behind a
                // TLS-terminating proxy (Render, Railway) the app sees plain
                // HTTP, so the default would silently drop the header in
                // production. Browsers ignore HSTS received over plain HTTP,
                // which makes sending it on every response safe.
                .httpStrictTransportSecurity(hsts -> hsts
                        .requestMatcher(AnyRequestMatcher.INSTANCE)
                        .includeSubDomains(true)
                        .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        SWAGGER_UI, new ContentSecurityPolicyHeaderWriter(SWAGGER_UI_CSP)))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(SWAGGER_UI), new ContentSecurityPolicyHeaderWriter(API_CSP)));
    }
}
