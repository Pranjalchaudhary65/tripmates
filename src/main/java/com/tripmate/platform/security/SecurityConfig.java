package com.tripmate.platform.security;

import com.tripmate.identity.controller.RegistrationController;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Deny-by-default: the only anonymous routes are the health probes, the
 * OpenAPI document and its UI, the container's internal error dispatch, and
 * the POST-only sign-up and email-verification routes.
 * Every other path - including /api/v1 paths that do not exist - answers 401,
 * so an anonymous caller cannot probe which endpoints exist.
 *
 * <p>Security rejections happen in the filter chain, before Spring MVC, so
 * {@code @RestControllerAdvice} never sees them on its own. Both handlers
 * below hand the exception to MVC's exception resolver, which routes it to
 * {@code GlobalExceptionHandler} and gives 401/403 the same ProblemDetail
 * body as every other error.
 *
 * <p>Response headers come from {@link SecurityHeaders}; CORS from
 * {@link ApiCorsFilter}, which runs straight after the header writer so a
 * preflight is answered before any authentication or authorization rule.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    /** Sign-up and email verification: reachable before an account or session exists. */
    static final String[] ANONYMOUS_AUTH_ROUTES = {
            ApiPaths.V1 + RegistrationController.BASE + RegistrationController.REGISTER,
            ApiPaths.V1 + RegistrationController.BASE + RegistrationController.VERIFY_EMAIL,
            ApiPaths.V1 + RegistrationController.BASE + RegistrationController.RESEND_VERIFICATION,
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsProperties corsProperties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        http
                .headers(SecurityHeaders.policy())
                // Spring's own CORS configurer is switched off so ApiCorsFilter
                // is the only CORS filter in the chain, whatever beans are added later.
                .cors(AbstractHttpConfigurer::disable)
                .addFilterAfter(new ApiCorsFilter(corsProperties, exceptionResolver), HeaderWriterFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.POST, ANONYMOUS_AUTH_ROUTES).permitAll()
                        .anyRequest().authenticated())
                // CSRF abuses credentials the browser attaches on its own
                // (cookies). These routes neither read nor set any, so there
                // is nothing for a cross-site request to ride on. Every other
                // state-changing route keeps CSRF protection.
                .csrf(csrf -> csrf.ignoringRequestMatchers(ANONYMOUS_AUTH_ROUTES))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            exceptionResolver.resolveException(request, response, null, ex);
                        })
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));
        return http.build();
    }
}
