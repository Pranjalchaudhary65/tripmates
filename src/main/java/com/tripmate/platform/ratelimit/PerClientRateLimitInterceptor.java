package com.tripmate.platform.ratelimit;

import com.tripmate.platform.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Charges one token per request to the caller's IP (or IPv6 /64) bucket
 * before the handler runs - before the body is even read or validated, so a
 * malformed request costs a token too.
 */
public final class PerClientRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    private final String bucket;

    private final RateLimit limit;

    public PerClientRateLimitInterceptor(RateLimiter rateLimiter, String bucket, RateLimit limit) {
        this.rateLimiter = rateLimiter;
        this.bucket = bucket;
        this.limit = limit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        rateLimiter.consume(bucket, ClientAddress.of(request).rateLimitKey(), limit);
        return true;
    }
}
