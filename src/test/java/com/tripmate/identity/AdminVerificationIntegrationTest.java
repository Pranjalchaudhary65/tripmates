package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
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
import java.util.List;
import java.util.UUID;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AdminVerificationIntegrationTest {

    private static final String QUEUE = "/api/v1/admin/verifications";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private LocalHttp adminHttp;

    private String adminToken;

    private String adminId;

    @BeforeEach
    void signInAsAdmin() {
        adminHttp = new LocalHttp(port);
        String email = uniqueEmail();
        new RegistrationApi(adminHttp, mailpit).registerVerified(email);
        SessionApi.Session session = new SessionApi(adminHttp).signIn(email, STRONG_PASSWORD);
        TwoFactorApi twoFactor = new TwoFactorApi(adminHttp);
        var authenticator = twoFactor.enrol(session.accessToken(), STRONG_PASSWORD, clock).authenticator();
        jdbc.update("update users set role = 'ADMIN' where id = ?::uuid", session.userId());
        String challengeId = TwoFactorApi.challengeId(new LoginApi(adminHttp).login(email, STRONG_PASSWORD));
        HttpResponse<String> signedIn = twoFactor.secondFactor(challengeId, authenticator.nextCode());
        adminToken = JsonPath.read(signedIn.body(), "$.session.accessToken");
        adminId = session.userId();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void theQueueShowsAFlaggedSelfieBesideItsProfilePhotoThroughLinksThatWork() {
        String userId = userWithPendingSelfie();

        HttpResponse<String> queue = queuePageContaining(userId);

        List<String> ids = JsonPath.read(queue.body(), "$.items[?(@.userId == '" + userId + "')].verificationId");
        assertThat(ids).hasSize(1);
        String selfie = JsonPath.<List<String>>read(queue.body(),
                "$.items[?(@.userId == '" + userId + "')].selfie.path").getFirst();
        String photo = JsonPath.<List<String>>read(queue.body(),
                "$.items[?(@.userId == '" + userId + "')].profilePhoto.path").getFirst();
        assertThat(adminHttp.getBytes("/api/v1" + selfie).statusCode()).isEqualTo(200);
        assertThat(adminHttp.getBytes("/api/v1" + photo).statusCode()).isEqualTo(200);
    }

    @Test
    void approvingRaisesTheAccountToSelfieMatchAndCannotBeDoneTwice() {
        String userId = userWithPendingSelfie();
        String verificationId = pendingVerificationOf(userId);

        HttpResponse<String> approved = decide(verificationId, "APPROVE");
        HttpResponse<String> again = decide(verificationId, "REJECT");

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(approved.body(), "$.status")).isEqualTo("APPROVED");
        assertThat(tierOf(userId)).isEqualTo("SELFIE_MATCH");
        assertThat(jdbc.queryForObject("select reviewer_id::text from identity_verifications where id = ?::uuid",
                String.class, verificationId)).isEqualTo(adminId);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(again)).isEqualTo("verification_already_decided");
    }

    @Test
    void rejectingRecordsAFixedReasonAndLeavesTheTierAlone() {
        String userId = userWithPendingSelfie();

        HttpResponse<String> rejected = decide(pendingVerificationOf(userId), "REJECT");

        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(tierOf(userId)).isEqualTo("PHONE");
        assertThat(jdbc.queryForObject("""
                select rejection_reason from identity_verifications
                where user_id = ?::uuid and tier = 'SELFIE_MATCH'""", String.class, userId))
                .isEqualTo("REVIEWER_REJECTED");
    }

    @Test
    void anAdminCannotDecideTheirOwnSelfie() {
        VerificationApi own = new VerificationApi(adminHttp, mailpit);
        own.uploadProfilePhoto(adminToken, TestImages.profilePhoto());
        String email = jdbc.queryForObject("select email from users where id = ?::uuid", String.class, adminId);
        own.verifyPhone(email, adminToken);
        own.selfie(adminToken, 2);

        HttpResponse<String> response = decide(pendingVerificationOf(adminId), "APPROVE");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(SessionApi.code(response)).isEqualTo("cannot_review_own_verification");
        assertThat(tierOf(adminId)).isEqualTo("PHONE");
    }

    @Test
    void anUnknownVerificationIsNotFound() {
        HttpResponse<String> response = decide(UUID.randomUUID().toString(), "APPROVE");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(SessionApi.code(response)).isEqualTo("not_found");
    }

    /** Walks the queue, oldest first, to the page holding this user's selfie. */
    private HttpResponse<String> queuePageContaining(String userId) {
        for (int page = 0; ; page++) {
            HttpResponse<String> response = adminHttp.get(QUEUE + "?size=50&page=" + page, bearer());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(JsonPath.<Integer>read(response.body(), "$.size")).isEqualTo(50);
            List<Object> found = JsonPath.read(response.body(), "$.items[?(@.userId == '" + userId + "')]");
            if (!found.isEmpty()) {
                return response;
            }
            int totalPages = JsonPath.read(response.body(), "$.totalPages");
            assertThat(page + 1).as("not in the queue").isLessThan(totalPages);
        }
    }

    private HttpResponse<String> decide(String verificationId, String decision) {
        return adminHttp.postJson(QUEUE + "/" + verificationId + "/decision", """
                {"decision":"%s"}""".formatted(decision), bearer());
    }

    private String userWithPendingSelfie() {
        LocalHttp http = new LocalHttp(port);
        String email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        SessionApi.Session session = new SessionApi(http).signIn(email, STRONG_PASSWORD);
        VerificationApi verification = new VerificationApi(http, mailpit);
        verification.uploadProfilePhoto(session.accessToken(), TestImages.profilePhoto());
        verification.verifyPhone(email, session.accessToken());
        HttpResponse<String> pending = verification.selfie(session.accessToken(), 2);
        assertThat(JsonPath.<String>read(pending.body(), "$.selfie.status")).isEqualTo("PENDING");
        return session.userId();
    }

    private String pendingVerificationOf(String userId) {
        return jdbc.queryForObject("""
                select id::text from identity_verifications
                where user_id = ?::uuid and tier = 'SELFIE_MATCH' and status = 'PENDING'""", String.class, userId);
    }

    private String tierOf(String userId) {
        return jdbc.queryForObject("select id_verification_tier from users where id = ?::uuid", String.class, userId);
    }

    private String[] bearer() {
        return VerificationApi.bearer(adminToken);
    }
}
