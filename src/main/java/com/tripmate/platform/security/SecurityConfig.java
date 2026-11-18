package com.tripmate.platform.security;

import com.tripmate.identity.controller.LoginController;
import com.tripmate.identity.controller.RegistrationController;
import com.tripmate.identity.controller.SessionController;
import com.tripmate.identity.service.SessionService;
import com.tripmate.identity.token.AccessTokenDecoderConfig;
import com.tripmate.identity.token.SessionLivenessFilter;
import com.tripmate.platform.web.ApiPaths;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Deny-by-default: the only anonymous routes are the health probes, the
 * OpenAPI document and its UI, the container's internal error dispatch, and
 * the POST-only sign-up, email-verification, login and refresh routes.
 * Every other path - including /api/v1 paths that do not exist - needs a
 * valid bearer access token whose session is still alive, and answers 401
 * otherwise, so an anonymous caller cannot probe which endpoints exist.
 *
 * <p>Filter order inside the chain: response headers, then CORS (a
 * preflight is answered before anything else), then the CSRF header check
 * on refresh, then bearer token verification, then the session liveness
 * check, then the authorization rules.
 *
 * <p>Security rejections happen in the filter chain, before Spring MVC, so
 * {@code @RestControllerAdvice} never sees them on its own. Every rejection
 * below is handed to MVC's exception resolver, which routes it to
 * {@code GlobalExceptionHandler} and gives it the same ProblemDetail body as
 * every other error.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    /** Sign-up, email verification, login and refresh: reachable before an access token exists. */
    static final String[] ANONYMOUS_AUTH_ROUTES = {
            ApiPaths.V1 + LoginController.BASE + LoginController.LOGIN,
            ApiPaths.V1 + RegistrationController.BASE + RegistrationController.REGISTER,
            ApiPaths.V1 + RegistrationController.BASE + RegistrationController.VERIFY_EMAIL,
            ApiPaths.V1 + RegistrationController.BASE + RegistrationController.RESEND_VERIFICATION,
            ApiPaths.V1 + SessionController.BASE + SessionController.REFRESH,
    };

    static final String[] PUBLIC_GET_ROUTES = {
            "/actuator/health", "/actuator/health/**",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
    };

    /** The one route that acts on a cookie and changes state, so the one that needs a CSRF check. */
    static final String REFRESH_ROUTE = ApiPaths.V1 + SessionController.BASE + SessionController.REFRESH;

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsProperties corsProperties,
            JwtDecoder accessTokenDecoder,
            SessionService sessions,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        RequestMatcher publicRoutes = publicRoutes();
        AuthenticationEntryPoint entryPoint = entryPoint(exceptionResolver);
        AccessDeniedHandler accessDenied = (request, response, ex) ->
                exceptionResolver.resolveException(request, response, null, ex);

        http
                .headers(SecurityHeaders.policy())
                // Spring's own CORS configurer is switched off so ApiCorsFilter
                // is the only CORS filter in the chain, whatever beans are added later.
                .cors(AbstractHttpConfigurer::disable)
                .addFilterAfter(new ApiCorsFilter(corsProperties, exceptionResolver), HeaderWriterFilter.class)
                .addFilterAfter(new CsrfHeaderFilter(
                        PathPatternRequestMatcher.pathPattern(HttpMethod.POST, REFRESH_ROUTE), exceptionResolver),
                        ApiCorsFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(publicRoutes).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(ignoringAuthorizationOn(publicRoutes))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied)
                        .jwt(jwt -> jwt
                                .decoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(AccessTokenDecoderConfig.authenticationConverter())))
                .addFilterAfter(new SessionLivenessFilter(sessions, entryPoint, exceptionResolver),
                        BearerTokenAuthenticationFilter.class)
                // Spring's CSRF filter protects cookie sessions, and there are
                // none: the session policy is stateless and the API is
                // authenticated by a bearer header, which a browser never
                // attaches on its own, so a cross-site request has nothing to
                // ride on. The two cookies that do exist are covered where
                // they are read: tm_refresh by CsrfHeaderFilter above, and
                // tm_device (login) by SameSite=Strict - it only chooses which
                // lockout counter applies and authenticates nothing.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied));
        return http.build();
    }

    private static RequestMatcher publicRoutes() {
        List<RequestMatcher> matchers = new ArrayList<>();
        for (String route : PUBLIC_GET_ROUTES) {
            matchers.add(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, route));
        }
        for (String route : ANONYMOUS_AUTH_ROUTES) {
            matchers.add(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, route));
        }
        return new OrRequestMatcher(matchers);
    }

    /**
     * Public routes never look at the Authorization header. Otherwise a
     * client still holding an expired token would get 401 from the login
     * route itself, the one route that could fix it, and a bad token sent to
     * refresh would fail before the cookie was ever read.
     */
    private static BearerTokenResolver ignoringAuthorizationOn(RequestMatcher publicRoutes) {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> publicRoutes.matches(request) ? null : header.resolve(request);
    }

    /**
     * RFC 6750: a missing token gets a bare {@code Bearer} challenge, a
     * rejected one adds {@code error="invalid_token"} so a client knows to
     * refresh. No error_description: why a token failed (expired, bad
     * signature, ended session) is for the server log, not the caller.
     */
    private static AuthenticationEntryPoint entryPoint(HandlerExceptionResolver exceptionResolver) {
        return (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    ex instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
            exceptionResolver.resolveException(request, response, null, ex);
        };
    }
}
