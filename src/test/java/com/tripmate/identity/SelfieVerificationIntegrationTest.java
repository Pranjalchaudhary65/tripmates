package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.LocalHttp.Part;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class SelfieVerificationIntegrationTest {

    private static final String API = "/api/v1";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ObjectStorage storage;

    private LocalHttp http;

    private VerificationApi verification;

    private String email;

    private SessionApi.Session session;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
        verification = new VerificationApi(http, mailpit);
        email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        session = new SessionApi(http).signIn(email, STRONG_PASSWORD);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void theStoredProfilePhotoIsAFreshJpegWithoutTheUploadsExif() {
        byte[] upload = TestImages.jpegWithExif();
        assertThat(new String(upload, StandardCharsets.ISO_8859_1)).contains("Exif", "GPSLatitude");

        HttpResponse<String> response = verification.uploadProfilePhoto(token(), upload);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String key = profilePhotoKey();
        assertThat(key).matches("profile-photos/[0-9a-f-]{36}\\.jpg");
        String stored = new String(storage.get(key).orElseThrow(), StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain("Exif", "GPSLatitude", "secret-home-address");
        assertThat(stored).startsWith("ÿØÿ");
    }

    @Test
    void theProfilePhotoLinkServesTheImageAndATamperedOrExpiredLinkDoesNot() {
        HttpResponse<String> response = verification.uploadProfilePhoto(token(), TestImages.profilePhoto());
        String path = JsonPath.read(response.body(), "$.profilePhoto.path");

        HttpResponse<byte[]> image = http.getBytes(API + path);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(image.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(image.body()).isEqualTo(storage.get(profilePhotoKey()).orElseThrow());

        String tampered = path.substring(0, path.length() - 2) + (path.endsWith("A") ? "BB" : "AA");
        assertThat(http.get(API + tampered).statusCode()).isEqualTo(404);

        clock.advance(Duration.ofMinutes(6));
        HttpResponse<String> expired = http.get(API + path);
        assertThat(expired.statusCode()).isEqualTo(404);
        assertThat(SessionApi.code(expired)).isEqualTo("not_found");
    }

    @Test
    void anythingButAJpegOrPngIsRefusedWhateverItClaimsToBe() {
        HttpResponse<String> gif = http.multipart("PUT", VerificationApi.PROFILE_PHOTO,
                List.of(Part.jpeg("photo", TestImages.gif())), bearer());
        HttpResponse<String> text = http.multipart("PUT", VerificationApi.PROFILE_PHOTO,
                List.of(Part.jpeg("photo", "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8))), bearer());
        HttpResponse<String> png = http.multipart("PUT", VerificationApi.PROFILE_PHOTO,
                List.of(Part.file("photo", "photo.png", "image/png", TestImages.png(0))), bearer());

        assertThat(gif.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(gif)).isEqualTo("invalid_image");
        assertThat(text.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(text)).isEqualTo("invalid_image");
        assertThat(png.statusCode()).as("a real PNG is fine").isEqualTo(200);
    }

    @Test
    void aDecompressionBombIsRefusedFromItsHeader() {
        HttpResponse<String> response = verification.uploadProfilePhoto(token(), TestImages.decompressionBomb());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(response)).isEqualTo("invalid_image");
    }

    @Test
    void anOversizedUploadIsRefusedWith413() {
        byte[] large = new byte[5 * 1024 * 1024 + 64 * 1024];
        System.arraycopy(TestImages.profilePhoto(), 0, large, 0, 3);

        HttpResponse<String> response = verification.uploadProfilePhoto(token(), large);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(profilePhotoKey()).isNull();
    }

    @Test
    void aMatchingLiveSelfieIsApprovedAtOnceAndRecordedForTheTrustScore() {
        verification.uploadProfilePhoto(token(), TestImages.profilePhoto());
        verification.verifyPhone(email, token());

        HttpResponse<String> response = verification.selfie(token(), 0);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.tier")).isEqualTo("SELFIE_MATCH");
        assertThat(JsonPath.<String>read(response.body(), "$.selfie.status")).isEqualTo("APPROVED");
        assertThat(response.body()).as("the score is never shown to the user").doesNotContain("similarity");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                where aggregate_id = ?::uuid and event_type = 'VERIFICATION_TIER_CHANGE'
                  and payload ->> 'to' = 'SELFIE_MATCH'""", Integer.class, userId())).isEqualTo(1);
        Map<String, Object> row = latestSelfie();
        assertThat(row.get("selfie_image_key")).asString().matches("selfies/[0-9a-f-]{36}\\.jpg");
        assertThat(((Timestamp) row.get("purge_due_at")).toInstant())
                .isEqualTo(((Timestamp) row.get("created_at")).toInstant().plus(Duration.ofDays(7)));
    }

    @Test
    void theSameStillPhotoSentTwiceFailsTheLivenessCheck() {
        readyForSelfie();
        String challengeId = verification.challengeId(token());

        HttpResponse<String> response = verification.submitSelfie(token(), challengeId, TestImages.jpeg(0),
                TestImages.jpeg(0));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.selfie.status")).isEqualTo("REJECTED");
        assertThat(JsonPath.<String>read(response.body(), "$.selfie.rejectionReason")).isEqualTo("LIVENESS_FAILED");
        assertThat(JsonPath.<String>read(response.body(), "$.tier")).isEqualTo("PHONE");
    }

    @Test
    void aClearMismatchIsRejectedWithoutAReviewer() {
        readyForSelfie();

        HttpResponse<String> response = verification.selfie(token(), 8);

        assertThat(JsonPath.<String>read(response.body(), "$.selfie.status")).isEqualTo("REJECTED");
        assertThat(JsonPath.<String>read(response.body(), "$.selfie.rejectionReason")).isEqualTo("LOW_SIMILARITY");
    }

    @Test
    void anAmbiguousSelfieWaitsForAReviewerAndBlocksAnotherAttempt() {
        readyForSelfie();

        HttpResponse<String> response = verification.selfie(token(), 2);

        assertThat(JsonPath.<String>read(response.body(), "$.selfie.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(response.body(), "$.tier")).isEqualTo("PHONE");
        assertThat(((Number) latestSelfie().get("similarity_score")).doubleValue()).isEqualTo(0.75);
        HttpResponse<String> again = verification.startSelfie(token());
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(again)).isEqualTo("selfie_review_pending");
    }

    @Test
    void aCheckIsGoodForOneSubmissionByItsOwnerWithinItsTimeLimit() {
        readyForSelfie();
        String used = verification.challengeId(token());
        verification.submitSelfie(token(), used, TestImages.jpeg(0), TestImages.jpeg(0));

        HttpResponse<String> replayed = verification.submitSelfie(token(), used, TestImages.jpeg(0),
                TestImages.jpeg(1));
        assertThat(replayed.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(replayed)).isEqualTo("liveness_challenge_invalid");

        String late = verification.challengeId(token());
        clock.advance(Duration.ofMinutes(3));
        HttpResponse<String> expired = verification.submitSelfie(token(), late, TestImages.jpeg(0),
                TestImages.jpeg(1));
        assertThat(expired.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(expired)).isEqualTo("liveness_challenge_invalid");
        clock.reset();

        String mine = verification.challengeId(token());
        LocalHttp otherHttp = new LocalHttp(port);
        String otherEmail = uniqueEmail();
        new RegistrationApi(otherHttp, mailpit).registerVerified(otherEmail);
        SessionApi.Session other = new SessionApi(otherHttp).signIn(otherEmail, STRONG_PASSWORD);
        HttpResponse<String> borrowed = new VerificationApi(otherHttp, mailpit).submitSelfie(other.accessToken(),
                mine, TestImages.jpeg(0), TestImages.jpeg(1));
        assertThat(borrowed.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(borrowed)).isEqualTo("liveness_challenge_invalid");
        assertThat(jdbc.queryForObject("select count(*) from identity_verifications where user_id = ?::uuid",
                Integer.class, other.userId())).isZero();
    }

    @Test
    void theSelfieCheckNeedsAVerifiedPhoneAndAProfilePhoto() {
        HttpResponse<String> noPhone = verification.startSelfie(token());
        assertThat(noPhone.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(noPhone)).isEqualTo("phone_verification_required");

        verification.verifyPhone(email, token());
        HttpResponse<String> noPhoto = verification.startSelfie(token());
        assertThat(noPhoto.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(noPhoto)).isEqualTo("profile_photo_required");
    }

    @Test
    void replacingTheProfilePhotoDropsSelfieMatchAndWithdrawsAPendingReview() {
        verification.reachSelfieMatch(email, token());

        HttpResponse<String> replaced = verification.uploadProfilePhoto(token(), TestImages.jpeg(3));

        assertThat(JsonPath.<String>read(replaced.body(), "$.tier")).isEqualTo("PHONE");
        assertThat((Object) JsonPath.read(replaced.body(), "$.selfie.status")).isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                where aggregate_id = ?::uuid and payload ->> 'from' = 'SELFIE_MATCH' and payload ->> 'to' = 'PHONE'""",
                Integer.class, userId())).isEqualTo(1);

        HttpResponse<String> pending = verification.selfie(token(), 5);
        assertThat(JsonPath.<String>read(pending.body(), "$.selfie.status")).isEqualTo("PENDING");
        HttpResponse<String> again = verification.uploadProfilePhoto(token(), TestImages.profilePhoto());
        assertThat(JsonPath.<String>read(again.body(), "$.selfie.status")).isEqualTo("REJECTED");
        assertThat(JsonPath.<String>read(again.body(), "$.selfie.rejectionReason"))
                .isEqualTo("PROFILE_PHOTO_CHANGED");
    }

    @Test
    void theReplacedProfilePhotoFileIsDeleted() {
        verification.uploadProfilePhoto(token(), TestImages.profilePhoto());
        String first = profilePhotoKey();

        verification.uploadProfilePhoto(token(), TestImages.jpeg(1));

        assertThat(profilePhotoKey()).isNotEqualTo(first);
        assertThat(storage.get(first)).isEmpty();
    }

    @Test
    void theDatabaseRefusesATierTheRowHasNoEvidenceFor() {
        assertThatThrownBy(() -> jdbc.update(
                        "update users set id_verification_tier = 'SELFIE_MATCH' where id = ?::uuid", userId()))
                .hasMessageContaining("ck_users_tier_evidence");
    }

    private void readyForSelfie() {
        verification.uploadProfilePhoto(token(), TestImages.profilePhoto());
        verification.verifyPhone(email, token());
    }

    private Map<String, Object> latestSelfie() {
        return jdbc.queryForMap("""
                select * from identity_verifications
                where user_id = ?::uuid and tier = 'SELFIE_MATCH' order by created_at desc limit 1""", userId());
    }

    private String profilePhotoKey() {
        return jdbc.queryForObject("select profile_photo_key from users where id = ?::uuid", String.class, userId());
    }

    private String[] bearer() {
        return VerificationApi.bearer(token());
    }

    private String token() {
        return session.accessToken();
    }

    private String userId() {
        return session.userId();
    }
}
