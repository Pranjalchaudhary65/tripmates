package com.tripmate.identity.controller;

import com.tripmate.identity.service.AuthRateLimitProperties;
import com.tripmate.platform.ratelimit.PerClientRateLimitInterceptor;
import com.tripmate.platform.ratelimit.RateLimit;
import com.tripmate.platform.ratelimit.RateLimiter;
import com.tripmate.platform.web.ApiPaths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A per-IP bucket on every anonymous identity route, each with its own
 * budget. Every one of them can be abused without an account: login for
 * password guessing, register for Argon2id CPU and for emailing strangers,
 * verify-email for code guessing across many registrations, resend for
 * email volume, refresh for load on the session store (it is reachable with
 * nothing but a cookie, real or made up). The per-account caps on codes stay
 * as well; this is the layer above them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthRateLimitProperties.class)
class AuthRateLimitConfig implements WebMvcConfigurer {

    private final RateLimiter rateLimiter;

    private final AuthRateLimitProperties limits;

    AuthRateLimitConfig(RateLimiter rateLimiter, AuthRateLimitProperties limits) {
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        limit(registry, "login-ip", LoginController.BASE + LoginController.LOGIN, limits.loginPerIp());
        limit(registry, "register-ip",
                RegistrationController.BASE + RegistrationController.REGISTER, limits.registerPerIp());
        limit(registry, "verify-email-ip",
                RegistrationController.BASE + RegistrationController.VERIFY_EMAIL, limits.verifyEmailPerIp());
        limit(registry, "resend-ip",
                RegistrationController.BASE + RegistrationController.RESEND_VERIFICATION, limits.resendPerIp());
        limit(registry, "refresh-ip", SessionController.BASE + SessionController.REFRESH, limits.refreshPerIp());
    }

    private void limit(InterceptorRegistry registry, String bucket, String route, RateLimit limit) {
        registry.addInterceptor(new PerClientRateLimitInterceptor(rateLimiter, bucket, limit))
                .addPathPatterns(ApiPaths.V1 + route);
    }
}
