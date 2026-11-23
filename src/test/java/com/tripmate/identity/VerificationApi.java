package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.LocalHttp.Part;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.TestImages;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls to the verification, profile photo and vouch routes, and the journeys between tiers. */
public final class VerificationApi {

    public static final String OVERVIEW = "/api/v1/verification";

    public static final String PHONE = "/api/v1/verification/phone";

    public static final String PHONE_CONFIRM = "/api/v1/verification/phone/confirm";

    public static final String SELFIE_CHALLENGE = "/api/v1/verification/selfie/challenge";

    public static final String SELFIE = "/api/v1/verification/selfie";

    public static final String PROFILE_PHOTO = "/api/v1/users/me/profile-photo";

    public static final String VOUCHES = "/api/v1/vouches";

    private final LocalHttp http;

    private final MailpitClient mailpit;

    public VerificationApi(LocalHttp http, MailpitClient mailpit) {
        this.http = http;
        this.mailpit = mailpit;
    }

    /** An Indian mobile number no other test is likely to use. */
    public static String uniquePhone() {
        return "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    public HttpResponse<String> overview(String accessToken) {
        return http.get(OVERVIEW, bearer(accessToken));
    }

    public HttpResponse<String> sendPhoneCode(String accessToken, String number) {
        return http.postJson(PHONE, """
                {"phoneNumber":"%s"}""".formatted(number), bearer(accessToken));
    }

    public HttpResponse<String> confirmPhone(String accessToken, String verificationId, String code) {
        return http.postJson(PHONE_CONFIRM, """
                {"verificationId":"%s","code":"%s"}""".formatted(verificationId, code), bearer(accessToken));
    }

    /**
     * Sends a code and reads it from the inbox. {@code codesSoFar} is how many
     * phone codes this address had already received.
     */
    public PendingPhone requestPhoneCode(String email, String accessToken, String number, int codesSoFar) {
        HttpResponse<String> sent = sendPhoneCode(accessToken, number);
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(202);
        String code = mailpit.awaitPhoneCode(email, codesSoFar + 1);
        return new PendingPhone(JsonPath.read(sent.body(), "$.verificationId"), code);
    }

    /** Takes the account to the PHONE tier with a fresh number; returns the number. */
    public String verifyPhone(String email, String accessToken) {
        String number = uniquePhone();
        PendingPhone pending = requestPhoneCode(email, accessToken, number, 0);
        HttpResponse<String> confirmed = confirmPhone(accessToken, pending.verificationId(), pending.code());
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return number;
    }

    public HttpResponse<String> uploadProfilePhoto(String accessToken, byte[] image) {
        return http.multipart("PUT", PROFILE_PHOTO, List.of(Part.jpeg("photo", image)), bearer(accessToken));
    }

    public HttpResponse<String> startSelfie(String accessToken) {
        return http.post(SELFIE_CHALLENGE, bearer(accessToken));
    }

    public HttpResponse<String> submitSelfie(String accessToken, String challengeId, byte[] neutral, byte[] prompt) {
        return http.multipart("POST", SELFIE, List.of(Part.text("challengeId", challengeId),
                Part.jpeg("neutralFrame", neutral), Part.jpeg("promptFrame", prompt)), bearer(accessToken));
    }

    public String challengeId(String accessToken) {
        HttpResponse<String> started = startSelfie(accessToken);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        return JsonPath.read(started.body(), "$.challengeId");
    }

    /** Starts a check and submits a neutral frame with {@code reversedRows}; the prompt frame differs by one row. */
    public HttpResponse<String> selfie(String accessToken, int reversedRows) {
        int promptRows = reversedRows == 8 ? 7 : reversedRows + 1;
        return submitSelfie(accessToken, challengeId(accessToken), TestImages.jpeg(reversedRows),
                TestImages.jpeg(promptRows));
    }

    /** A verified email account that has just signed in, taken all the way to SELFIE_MATCH. */
    public void reachSelfieMatch(String email, String accessToken) {
        HttpResponse<String> photo = uploadProfilePhoto(accessToken, TestImages.profilePhoto());
        assertThat(photo.statusCode()).as(photo.body()).isEqualTo(200);
        verifyPhone(email, accessToken);
        HttpResponse<String> matched = selfie(accessToken, 0);
        assertThat(matched.statusCode()).as(matched.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(matched.body(), "$.tier")).isEqualTo("SELFIE_MATCH");
    }

    public HttpResponse<String> vouches(String accessToken) {
        return http.get(VOUCHES, bearer(accessToken));
    }

    public HttpResponse<String> vouch(String accessToken, String vouchedUserId) {
        return http.postJson(VOUCHES, """
                {"vouchedUserId":"%s"}""".formatted(vouchedUserId), bearer(accessToken));
    }

    public HttpResponse<String> revokeVouch(String accessToken, String vouchId) {
        return http.delete(VOUCHES + "/" + vouchId, bearer(accessToken));
    }

    static String[] bearer(String accessToken) {
        return new String[] {"Authorization", "Bearer " + accessToken};
    }

    public record PendingPhone(String verificationId, String code) {
    }
}
