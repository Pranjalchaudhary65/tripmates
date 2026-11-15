package com.tripmate.identity.captcha;

import com.tripmate.platform.web.ClientAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code tripmate.identity.captcha.provider=none}: accepts every request. Rate
 * limits and lockout still apply in full; only the extra human check after
 * repeated failures is missing, and startup says so.
 */
@Component
@ConditionalOnProperty(prefix = "tripmate.identity.captcha", name = "provider", havingValue = "none")
class NoCaptchaVerifier implements CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(NoCaptchaVerifier.class);

    NoCaptchaVerifier() {
        log.warn("No CAPTCHA provider is configured; login will not challenge clients after repeated failures");
    }

    @Override
    public boolean verify(String token, ClientAddress client) {
        return true;
    }
}
