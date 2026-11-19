package com.tripmate.identity.service;

import com.tripmate.platform.ratelimit.RateLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Request limits on the anonymous identity routes, refresh included: it takes
 * no bearer token, only a cookie. Per-IP limits apply to every route; login
 * is also limited per account, keyed on the typed email whether or not an
 * account exists, so the limit itself reveals nothing.
 *
 * <p>{@code twoFactorPerAccount} is the one authenticated limit: changing the
 * second factor checks a password and a code, which a stolen access token
 * could otherwise use as a guessing oracle.
 */
@ConfigurationProperties("tripmate.identity.rate-limit")
public record AuthRateLimitProperties(RateLimit loginPerIp, RateLimit loginPerAccount, RateLimit registerPerIp,
                                      RateLimit verifyEmailPerIp, RateLimit resendPerIp,
                                      RateLimit refreshPerIp, RateLimit secondFactorPerIp,
                                      RateLimit twoFactorPerAccount) {

    public AuthRateLimitProperties {
        Objects.requireNonNull(loginPerIp, "tripmate.identity.rate-limit.login-per-ip is required");
        Objects.requireNonNull(loginPerAccount, "tripmate.identity.rate-limit.login-per-account is required");
        Objects.requireNonNull(registerPerIp, "tripmate.identity.rate-limit.register-per-ip is required");
        Objects.requireNonNull(verifyEmailPerIp, "tripmate.identity.rate-limit.verify-email-per-ip is required");
        Objects.requireNonNull(resendPerIp, "tripmate.identity.rate-limit.resend-per-ip is required");
        Objects.requireNonNull(refreshPerIp, "tripmate.identity.rate-limit.refresh-per-ip is required");
        Objects.requireNonNull(secondFactorPerIp, "tripmate.identity.rate-limit.second-factor-per-ip is required");
        Objects.requireNonNull(twoFactorPerAccount,
                "tripmate.identity.rate-limit.two-factor-per-account is required");
    }
}
