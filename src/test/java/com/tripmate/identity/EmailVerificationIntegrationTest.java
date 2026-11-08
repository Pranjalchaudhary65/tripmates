package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static com.tripmate.identity.RegistrationApi.verificationId;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class EmailVerificationIntegrationTest {

    private static final int MAX_ATTEMPTS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MailpitClient mailpit;

    private RegistrationApi api;

    @BeforeEach
    void setUp() {
        api = new RegistrationApi(new LocalHttp(port), mailpit);
    }

    @Test
    void correctCodeVerifiesTheEmailAndRaisesTheTier() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);

        HttpResponse<String> response = api.verify(id, code);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.emailVerified")).isTrue();
        assertThat(JsonPath.<String>read(response.body(), "$.verificationTier")).isEqualTo("EMAIL");
        Map<String, Object> user = userRow(email);
        assertThat(user.get("email_verified_at")).isNotNull();
        assertThat(user.get("id_verification_tier")).isEqualTo("EMAIL");
        assertThat(otpRow(id).get("consumed_at")).isNotNull();
    }

    @Test
    void aUsedCodeCannotBeRedeemedTwice() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);

        assertThat(api.verify(id, code).statusCode()).isEqualTo(200);
        assertThat(api.verify(id, code).statusCode()).isEqualTo(400);
    }

    @Test
    void wrongCodeCountsAttemptsUntilTheCodeIsDead() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);
        String wrong = wrongCode(code);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            assertThat(api.verify(id, wrong).statusCode()).isEqualTo(400);
            assertThat(attemptCount(id)).isEqualTo(attempt);
        }

        assertThat(api.verify(id, code).statusCode()).isEqualTo(400);
        assertThat(attemptCount(id)).isEqualTo(MAX_ATTEMPTS);
        assertThat(userRow(email).get("email_verified_at")).isNull();
    }

    @Test
    void correctCodeOnTheLastAttemptStillVerifies() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);
        String wrong = wrongCode(code);

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertThat(api.verify(id, wrong).statusCode()).isEqualTo(400);
        }
        assertThat(attemptCount(id)).isEqualTo(MAX_ATTEMPTS - 1);

        assertThat(api.verify(id, code).statusCode()).isEqualTo(200);
        assertThat(attemptCount(id)).isEqualTo(MAX_ATTEMPTS);
        assertThat(otpRow(id).get("consumed_at")).isNotNull();
        assertThat(userRow(email).get("id_verification_tier")).isEqualTo("EMAIL");
    }

    @Test
    void concurrentGuessesNeverGetMoreThanTheAttemptLimit() throws Exception {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);
        String wrong = wrongCode(code);

        int guesses = 12;
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            calls.add(() -> api.verify(id, wrong).statusCode());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(guesses)) {
            for (Future<Integer> result : pool.invokeAll(calls)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).containsOnly(400);
        assertThat(attemptCount(id)).isEqualTo(MAX_ATTEMPTS);
        assertThat(api.verify(id, code).statusCode()).isEqualTo(400);
    }

    @Test
    void expiredCodeIsRejectedEvenWhenCorrect() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);
        jdbc.update("update otp_codes set expires_at = now() - interval '1 second' where id = ?", id);

        assertThat(api.verify(id, code).statusCode()).isEqualTo(400);
        assertThat(userRow(email).get("email_verified_at")).isNull();
        assertThat(attemptCount(id)).isZero();
    }

    @Test
    void theCodeIsNeverStoredInPlainText() throws Exception {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);

        String wholeRow = jdbc.queryForObject("select row_to_json(o)::text from otp_codes o where id = ?",
                String.class, id);
        String storedHash = (String) otpRow(id).get("code_hash");
        String unkeyedSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));

        assertThat(wholeRow).doesNotContain(code);
        assertThat(storedHash).matches("[0-9a-f]{64}").isNotEqualToIgnoringCase(unkeyedSha256);
    }

    @Test
    void everyFailureGivesTheSameAnswer() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        String code = mailpit.awaitVerificationCode(email);

        Map<String, Object> wrong = withoutCorrelationId(api.verify(id, wrongCode(code)));
        Map<String, Object> unknown = withoutCorrelationId(api.verify(UUID.randomUUID(), code));
        jdbc.update("update otp_codes set expires_at = now() - interval '1 second' where id = ?", id);
        Map<String, Object> expired = withoutCorrelationId(api.verify(id, code));

        assertThat(wrong.get("code")).isEqualTo("invalid_verification_code");
        assertThat(unknown).isEqualTo(wrong);
        assertThat(expired).isEqualTo(wrong);
    }

    @Test
    void resendWithinTheCooldownKeepsTheCurrentCode() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));

        HttpResponse<String> resent = api.resend(id);

        assertThat(resent.statusCode()).isEqualTo(202);
        assertThat(verificationId(resent)).isEqualTo(id);
        assertThat(codesFor(email)).isOne();
    }

    @Test
    void resendAfterTheCooldownIssuesANewCodeAndRetiresTheOld() {
        String email = uniqueEmail();
        UUID firstId = verificationId(api.register(email));
        String firstCode = mailpit.awaitVerificationCode(email, 1);
        ageCodes(email);

        UUID secondId = verificationId(api.resend(firstId));
        String secondCode = mailpit.awaitVerificationCode(email, 2);

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(api.verify(firstId, firstCode).statusCode()).isEqualTo(400);
        assertThat(api.verify(secondId, secondCode).statusCode()).isEqualTo(200);
    }

    @Test
    void resendWithASupersededIdIsADecoy() {
        String email = uniqueEmail();
        UUID staleId = verificationId(api.register(email));
        verificationId(api.register(email));
        ageCodes(email);

        UUID answered = verificationId(api.resend(staleId));

        assertThat(jdbc.queryForObject("select count(*) from otp_codes where id = ?", Long.class, answered)).isZero();
        assertThat(codesFor(email)).isEqualTo(2);
    }

    @Test
    void resendForAVerifiedAccountIsADecoy() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        assertThat(api.verify(id, mailpit.awaitVerificationCode(email)).statusCode()).isEqualTo(200);
        ageCodes(email);

        UUID answered = verificationId(api.resend(id));

        assertThat(answered).isNotEqualTo(id);
        assertThat(codesFor(email)).isOne();
    }

    @Test
    void noMoreThanFiveCodesADay() {
        String email = uniqueEmail();
        UUID id = verificationId(api.register(email));
        for (int i = 0; i < 6; i++) {
            ageCodes(email);
            id = verificationId(api.resend(id));
        }

        assertThat(codesFor(email)).isEqualTo(5);
    }

    private long attemptCount(UUID id) {
        return ((Number) otpRow(id).get("attempt_count")).longValue();
    }

    private Map<String, Object> otpRow(UUID id) {
        return jdbc.queryForMap("select * from otp_codes where id = ?", id);
    }

    private Map<String, Object> userRow(String email) {
        return jdbc.queryForMap("select * from users where email = ? and deleted_at is null", email);
    }

    private long codesFor(String email) {
        return jdbc.queryForObject("""
                select count(*) from otp_codes o join users u on u.id = o.user_id
                where u.email = ?""", Long.class, email);
    }

    /** Moves every code for the account two minutes into the past, out of the resend cooldown but inside the day. */
    private void ageCodes(String email) {
        jdbc.update("""
                update otp_codes set created_at = created_at - interval '2 minutes'
                where user_id = (select id from users where email = ? and deleted_at is null)""", email);
    }

    private static String wrongCode(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private static Map<String, Object> withoutCorrelationId(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        body.remove("correlationId");
        return body;
    }
}
