package com.tripmate.support;

import com.tripmate.identity.captcha.CaptchaVerifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for a CAPTCHA provider so tests can see when login consults it:
 * {@link #FAILING_TOKEN} is rejected, anything else (including no token)
 * passes, as the default verifier does.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubCaptcha {

    public static final String FAILING_TOKEN = "captcha-not-solved";

    @Bean
    @Primary
    CaptchaVerifier stubCaptchaVerifier() {
        return (token, client) -> !FAILING_TOKEN.equals(token);
    }
}
