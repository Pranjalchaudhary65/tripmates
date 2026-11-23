package com.tripmate.identity;

import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.identity.verification.SelfiePurgeJob;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN.md §1 identity_verifications: no selfie image survives past its
 * retention window, whatever its verification decided.
 */
@IntegrationTest
class SelfiePurgeIntegrationTest {

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

    @Autowired
    private SelfiePurgeJob purgeJob;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void noSelfieImageSurvivesItsRetentionWindowInAnyOutcome() {
        String approved = userWithSelfie(0);
        String rejected = userWithSelfie(8);
        String pending = userWithSelfie(2);
        List<String> keys = List.of(selfieKey(approved), selfieKey(rejected), selfieKey(pending));
        keys.forEach(key -> assertThat(storage.get(key)).as(key).isPresent());

        clock.advance(Duration.ofDays(7).plusMinutes(1));
        purgeJob.purgeDue();

        for (String userId : List.of(approved, rejected, pending)) {
            Map<String, Object> row = selfieRow(userId);
            assertThat(row.get("selfie_image_key")).as("key of %s", userId).isNull();
            assertThat(row.get("purged_at")).as("purged_at of %s", userId).isNotNull();
            assertThat(row.get("status")).as("status of %s", userId).isNotEqualTo("PENDING");
        }
        keys.forEach(key -> assertThat(storage.get(key)).as(key).isEmpty());
    }

    @Test
    void aSelfieStillWaitingWhenItsWindowEndsIsRejectedAsExpired() {
        String pending = userWithSelfie(2);

        clock.advance(Duration.ofDays(8));
        purgeJob.purgeDue();

        Map<String, Object> row = selfieRow(pending);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("rejection_reason")).isEqualTo("REVIEW_EXPIRED");
        assertThat(row.get("reviewer_id")).isNull();
    }

    @Test
    void aSelfieInsideItsWindowIsKept() {
        String userId = userWithSelfie(0);
        String key = selfieKey(userId);

        clock.advance(Duration.ofDays(6));
        purgeJob.purgeDue();

        assertThat(selfieKey(userId)).isEqualTo(key);
        assertThat(storage.get(key)).isPresent();
    }

    @Test
    void runningThePurgeAgainChangesNothing() {
        String userId = userWithSelfie(0);
        clock.advance(Duration.ofDays(8));
        purgeJob.purgeDue();
        Object purgedAt = selfieRow(userId).get("purged_at");

        purgeJob.purgeDue();

        assertThat(selfieRow(userId).get("purged_at")).isEqualTo(purgedAt);
    }

    /** A new account with one selfie submitted whose neutral frame has {@code reversedRows}. */
    private String userWithSelfie(int reversedRows) {
        LocalHttp http = new LocalHttp(port);
        String email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        SessionApi.Session session = new SessionApi(http).signIn(email, STRONG_PASSWORD);
        VerificationApi verification = new VerificationApi(http, mailpit);
        verification.uploadProfilePhoto(session.accessToken(), TestImages.profilePhoto());
        verification.verifyPhone(email, session.accessToken());
        assertThat(verification.selfie(session.accessToken(), reversedRows).statusCode()).isEqualTo(200);
        return session.userId();
    }

    private Map<String, Object> selfieRow(String userId) {
        return jdbc.queryForMap("""
                select * from identity_verifications
                where user_id = ?::uuid and tier = 'SELFIE_MATCH' order by created_at desc limit 1""", userId);
    }

    private String selfieKey(String userId) {
        return (String) selfieRow(userId).get("selfie_image_key");
    }
}
