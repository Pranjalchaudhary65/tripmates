package com.tripmate.platform.security;

import com.tripmate.platform.logging.CorrelationId;
import com.tripmate.platform.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * CORS for {@code /api/v1/**}, placed in the security chain ahead of
 * authentication. A preflight carries no Authorization header, so if it
 * reached the authorization rules it would get a 401 and the browser would
 * block the real request behind it. Here it is answered - allowed or
 * rejected - before authentication runs.
 *
 * <p>Only exact origins from {@link CorsProperties} are allowed, with
 * credentials, because the refresh token travels as an httpOnly cookie.
 * Paths outside /api/v1 (actuator, OpenAPI, Swagger UI) get no CORS headers
 * at all: only the React app calls the API cross-origin.
 *
 * <p>Spring's stock processor rejects with a plain-text body. This one hands
 * the rejection to the MVC exception resolver instead, the same way
 * {@link SecurityConfig} handles 401 and 403, so a rejected cross-origin
 * request gets the same ProblemDetail body as every other error.
 */
public class ApiCorsFilter extends CorsFilter {

    private static final List<String> ALLOWED_METHODS = List.of(
            HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private static final List<String> ALLOWED_HEADERS =
            List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, CorrelationId.HEADER);

    private static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

    public ApiCorsFilter(CorsProperties properties, HandlerExceptionResolver exceptionResolver) {
        super(source(properties));
        setCorsProcessor(new ProblemRejectingCorsProcessor(exceptionResolver));
    }

    private static CorsConfigurationSource source(CorsProperties properties) {
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(properties.allowedOrigins());
        api.setAllowedMethods(ALLOWED_METHODS);
        api.setAllowedHeaders(ALLOWED_HEADERS);
        api.setExposedHeaders(List.of(CorrelationId.HEADER));
        api.setAllowCredentials(true);
        api.setMaxAge(PREFLIGHT_CACHE);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(ApiPaths.V1 + "/**", api);
        return source;
    }

    private static final class ProblemRejectingCorsProcessor extends DefaultCorsProcessor {

        private final HandlerExceptionResolver exceptionResolver;

        private ProblemRejectingCorsProcessor(HandlerExceptionResolver exceptionResolver) {
            this.exceptionResolver = exceptionResolver;
        }

        /**
         * The parent returns false when it rejects the request, and also when
         * the Origin header cannot be parsed - a case where it writes nothing
         * and would otherwise leave an empty 200. Both end as a 403 problem.
         */
        @Override
        public boolean processRequest(CorsConfiguration config, HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
            if (super.processRequest(config, request, response)) {
                return true;
            }
            exceptionResolver.resolveException(request, response, null,
                    new AccessDeniedException("Cross-origin request rejected"));
            return false;
        }

        /**
         * The parent grants whichever requested headers are allowed and drops
         * the rest, leaving the browser to fail the request later. A preflight
         * asking for any header outside the list is rejected here instead, so
         * the failure is an explicit 403 with a correlation ID in the logs.
         */
        @Override
        protected List<String> checkHeaders(CorsConfiguration config, List<String> requestHeaders) {
            List<String> allowed = super.checkHeaders(config, requestHeaders);
            if (allowed == null || requestHeaders == null) {
                return allowed;
            }
            long requested = requestHeaders.stream().filter(StringUtils::hasText).count();
            return allowed.size() == requested ? allowed : null;
        }

        /** Left empty on purpose: processRequest writes the rejection. */
        @Override
        protected void rejectRequest(ServerHttpResponse response) {
        }
    }
}
