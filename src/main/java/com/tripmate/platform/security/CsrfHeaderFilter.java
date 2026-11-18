package com.tripmate.platform.security;

import com.tripmate.platform.error.ApiProblemException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * CSRF defence for the routes that act on a cookie: the request must carry
 * {@code X-TripMates-CSRF: 1}.
 *
 * <p>This is the custom-request-header pattern. A page on another origin can
 * make a browser send a cookie-carrying POST (a form, an image, a plain
 * fetch), but it cannot add a custom header without a CORS preflight, and
 * {@link ApiCorsFilter} only approves the allowlisted origins. So a request
 * that has the header was either sent by our own app, or by a non-browser
 * client, which has no victim's cookie to abuse. A double-submit token would
 * need a cookie page scripts can read on the app's origin, which a separate
 * API host cannot set without widening the cookie to the parent domain; the
 * header gives the same guarantee without that.
 *
 * <p>It is one layer of three on the refresh route, with SameSite=Strict on
 * the cookie and the CORS origin check, so a gap in any one (an old browser
 * ignoring SameSite, a same-site sibling subdomain) is still covered.
 */
public final class CsrfHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-TripMates-CSRF";

    public static final String VALUE = "1";

    private final RequestMatcher cookieAuthenticatedRequests;

    private final HandlerExceptionResolver exceptionResolver;

    public CsrfHeaderFilter(RequestMatcher cookieAuthenticatedRequests, HandlerExceptionResolver exceptionResolver) {
        this.cookieAuthenticatedRequests = cookieAuthenticatedRequests;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (cookieAuthenticatedRequests.matches(request) && !VALUE.equals(request.getHeader(HEADER))) {
            exceptionResolver.resolveException(request, response, null,
                    new ApiProblemException(SecurityProblem.CSRF_REJECTED));
            return;
        }
        chain.doFilter(request, response);
    }
}
