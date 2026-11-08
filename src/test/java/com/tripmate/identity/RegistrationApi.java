package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.service.AgePolicy;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls the registration endpoints over real HTTP the way a client would. */
final class RegistrationApi {

    static final String REGISTER = "/api/v1/auth/register";

    static final String VERIFY_EMAIL = "/api/v1/auth/verify-email";

    static final String RESEND = "/api/v1/auth/verify-email/resend";

    static final String STRONG_PASSWORD = "violet-kettle-harbour-91";

    private final LocalHttp http;

    private final MailpitClient mailpit;

    RegistrationApi(LocalHttp http, MailpitClient mailpit) {
        this.http = http;
        this.mailpit = mailpit;
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** A date of birth that makes the user {@code years} old today, in the zone the age rule uses. */
    static LocalDate bornYearsAgo(int years) {
        return LocalDate.now(AgePolicy.LEGAL_ZONE).minusYears(years);
    }

    HttpResponse<String> register(String email, String password, LocalDate dateOfBirth) {
        return http.postJson(REGISTER, """
                {"email":"%s","password":"%s","fullName":"Asha Verma","dateOfBirth":"%s","gender":"FEMALE"}"""
                .formatted(email, password, dateOfBirth));
    }

    HttpResponse<String> register(String email) {
        return register(email, STRONG_PASSWORD, bornYearsAgo(25));
    }

    HttpResponse<String> verify(UUID verificationId, String code) {
        return http.postJson(VERIFY_EMAIL, """
                {"verificationId":"%s","code":"%s"}""".formatted(verificationId, code));
    }

    HttpResponse<String> resend(UUID verificationId) {
        return http.postJson(RESEND, """
                {"verificationId":"%s"}""".formatted(verificationId));
    }

    static UUID verificationId(HttpResponse<String> response) {
        return UUID.fromString(JsonPath.read(response.body(), "$.verificationId"));
    }

    /** Registers and verifies, leaving a verified account behind. */
    void registerVerified(String email) {
        HttpResponse<String> registered = register(email);
        assertThat(registered.statusCode()).isEqualTo(202);
        String code = mailpit.awaitVerificationCode(email);
        assertThat(verify(verificationId(registered), code).statusCode()).isEqualTo(200);
    }
}
