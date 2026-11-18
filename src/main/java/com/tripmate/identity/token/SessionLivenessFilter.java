package com.tripmate.identity.token;

import com.tripmate.identity.service.SessionService;
import com.tripmate.platform.error.RetryLaterException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Runs straight after the bearer token has been verified, and refuses it if
 * its session has been ended since it was issued: by logout, "revoke all
 * devices", or a detected refresh token reuse. Without this, an access token
 * would keep working for the rest of its 15 minutes after the user signed
 * out, or after a stolen refresh token had been caught.
 *
 * <p>The check is one Redis EXISTS on the family key, the same key a
 * refresh rotates, so there is no separate denylist to keep in step. It
 * fails closed: if Redis cannot answer, the request gets 503, never waved
 * through on the signature alone.
 *
 * <p>Not a Spring bean on purpose: as a bean, Spring Boot would also
 * register it as a servlet filter outside the security chain.
 */
public final class SessionLivenessFilter extends OncePerRequestFilter {

    private final SessionService sessions;

    private final AuthenticationEntryPoint entryPoint;

    private final HandlerExceptionResolver exceptionResolver;

    public SessionLivenessFilter(SessionService sessions, AuthenticationEntryPoint entryPoint,
                                 HandlerExceptionResolver exceptionResolver) {
        this.sessions = sessions;
        this.entryPoint = entryPoint;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            boolean live;
            try {
                live = sessions.isLive(SessionPrincipal.of(token.getToken()).sessionId());
            } catch (RetryLaterException unavailable) {
                SecurityContextHolder.clearContext();
                exceptionResolver.resolveException(request, response, null, unavailable);
                return;
            }
            if (!live) {
                SecurityContextHolder.clearContext();
                entryPoint.commence(request, response, new InvalidBearerTokenException("Session has ended"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
