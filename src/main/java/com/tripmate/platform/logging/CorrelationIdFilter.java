package com.tripmate.platform.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation ID: the caller's {@value CorrelationId#HEADER}
 * if it is well-formed, a random UUID otherwise. The ID is put in the MDC so
 * every log line written while handling the request carries it, stored as a
 * request attribute so the container's error dispatch reuses the same ID
 * instead of minting a second one, and echoed on the response before the
 * chain runs so it is present even when a later filter commits the response
 * early (a security rejection, for example).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = resolve(request);
        request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);

        String previous = MDC.get(CorrelationId.MDC_KEY);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previous);
            }
        }
    }

    /** Error dispatches to /error must log and respond under the original request's ID. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private static String resolve(HttpServletRequest request) {
        if (request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String inbound = request.getHeader(CorrelationId.HEADER);
        return CorrelationId.isAcceptable(inbound) ? inbound : UUID.randomUUID().toString();
    }
}
