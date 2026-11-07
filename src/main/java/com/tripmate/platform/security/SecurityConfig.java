package com.tripmate.platform.security;

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
 * OpenAPI document and its UI, and the container's internal error dispatch.
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
                        .anyRequest().authenticated())
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
