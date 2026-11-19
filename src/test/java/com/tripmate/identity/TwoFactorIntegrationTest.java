package com.tripmate.identity;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.tripmate.identity.crypto.DecryptionFailedException;
import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import com.tripmate.support.TestAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class TwoFactorIntegrationTest {

    private static final String BACKUP_CODE_FORMAT = "[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private FieldEncryption encryption;

    @Autowired
    private UserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private LocalHttp http;

    private SessionApi sessions;

    private LoginApi login;

    private TwoFactorApi twoFactor;

    private String email;

    @BeforeEach
    void setUp() {
        http = new LocalHttp(port);
        sessions = new SessionApi(http);
        login = new LoginApi(http);
        twoFactor = new TwoFactorApi(http);
        email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void enrolmentTurnsOnOnlyAfterAValidFirstCode() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();

        HttpResponse<String> setup = twoFactor.setup(token, STRONG_PASSWORD);

        assertThat(setup.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(setup.body());
        String secret = body.read("$.secret");
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(body.read("$.otpauthUri", String.class)).isEqualTo("otpauth://totp/TripMates:"
                + email.replace("@", "%40") + "?secret=" + secret
                + "&issuer=TripMates&algorithm=SHA1&digits=6&period=30");
        assertThat(body.read("$.digits", Integer.class)).isEqualTo(6);
        assertThat(body.read("$.period", Integer.class)).isEqualTo(30);
        assertStatus(token, false, true, 0);

        TestAuthenticator phone = new TestAuthenticator(secret, clock);
        HttpResponse<String> wrong = twoFactor.confirm(token, phone.wrongCode());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(wrong)).isEqualTo("invalid_totp_code");
        assertThat(twoFactor.confirm(token, "12ab56").statusCode()).isEqualTo(400);
        assertStatus(token, false, true, 0);
        assertThat(sessions.signIn(email, STRONG_PASSWORD).accessToken()).isNotBlank();

        HttpResponse<String> confirmed = twoFactor.confirm(token, phone.nextCode());

        assertThat(confirmed.statusCode()).isEqualTo(200);
        List<String> backupCodes = JsonPath.read(confirmed.body(), "$.backupCodes");
        assertThat(backupCodes).hasSize(10).allMatch(code -> code.matches(BACKUP_CODE_FORMAT));
        assertThat(new HashSet<>(backupCodes)).hasSize(10);
        assertThat(confirmed.body()).doesNotContain(secret);
        assertStatus(token, true, false, 10);
        assertThat(SessionApi.code(twoFactor.confirm(token, phone.nextCode()))).isEqualTo("two_factor_already_enabled");
        HttpResponse<String> setupAgain = twoFactor.setup(token, STRONG_PASSWORD);
        assertThat(setupAgain.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(setupAgain)).isEqualTo("two_factor_already_enabled");
        mailpit.awaitSubject(email, MailpitClient.TWO_FACTOR_ENABLED_SUBJECT);
        assertThat(mailpit.latestText(email, MailpitClient.TWO_FACTOR_ENABLED_SUBJECT))
                .doesNotContain(secret, backupCodes.getFirst());
    }

    @Test
    void setupNeedsThePasswordAndAStolenTokenAloneCannotStartIt() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();

        HttpResponse<String> response = twoFactor.setup(token, "not-the-password-at-all");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(response)).isEqualTo("reauthentication_failed");
        assertStatus(token, false, false, 0);
    }

    @Test
    void startingSetupAgainBeforeConfirmingReplacesTheFirstSecret() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        String first = JsonPath.read(twoFactor.setup(token, STRONG_PASSWORD).body(), "$.secret");
        String second = JsonPath.read(twoFactor.setup(token, STRONG_PASSWORD).body(), "$.secret");
        assertThat(second).isNotEqualTo(first);

        clock.advance(Duration.ofSeconds(30));
        String codeFromFirst = new TestAuthenticator(first, clock).currentCode();
        String codeFromSecond = new TestAuthenticator(second, clock).currentCode();

        assertThat(twoFactor.confirm(token, codeFromFirst).statusCode()).isEqualTo(400);
        assertThat(twoFactor.confirm(token, codeFromSecond).statusCode()).isEqualTo(200);
    }

    @Test
    void turningTwoFactorOnEndsEveryOtherSession() {
        SessionApi.Session laptop = sessions.signIn(email, STRONG_PASSWORD);
        SessionApi.Session phone = sessions.signIn(email, STRONG_PASSWORD);

        twoFactor.enrol(laptop.accessToken(), STRONG_PASSWORD, clock);

        assertThat(sessions.me(laptop.accessToken()).statusCode()).isEqualTo(200);
        assertThat(sessions.me(phone.accessToken()).statusCode()).isEqualTo(401);
        assertThat(sessions.refresh(phone.refreshToken()).statusCode()).isEqualTo(401);
        assertThat(revocation(phone.sessionId())).isEqualTo("SECURITY_CHANGE");
        assertThat(revocation(laptop.sessionId())).isNull();
    }

    @Test
    void theSecretIsStoredOnlyEncryptedAndNeverReturnedAgain() {
        SessionApi.Session session = sessions.signIn(email, STRONG_PASSWORD);
        String secret = JsonPath.read(twoFactor.setup(session.accessToken(), STRONG_PASSWORD).body(), "$.secret");
        byte[] secretBytes = TestAuthenticator.decodeBase32(secret);
        UUID userId = UUID.fromString(session.userId());

        byte[] stored = jdbc.queryForObject("select totp_secret_ciphertext from users where id = ?",
                byte[].class, userId);
        assertThat(stored).hasSize(1 + 12 + secretBytes.length + 16);
        assertThat(indexOf(stored, secretBytes)).isEqualTo(-1);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        UUID keyId = jdbc.queryForObject("select encryption_key_id from users where id = ?", UUID.class, userId);
        byte[] wrapped = jdbc.queryForObject(
                "select wrapped_dek from user_encryption_keys where id = ? and user_id = ? and active",
                byte[].class, keyId, userId);
        assertThat(wrapped).hasSize(1 + 8 + 12 + 32 + 16);

        assertThat(decrypt(userId, stored)).isEqualTo(secretBytes);

        twoFactor.enrol(session.accessToken(), STRONG_PASSWORD, clock);
        for (HttpResponse<String> response : List.of(twoFactor.status(session.accessToken()),
                sessions.me(session.accessToken()), twoFactor.setup(session.accessToken(), STRONG_PASSWORD))) {
            assertThat(response.body()).doesNotContain(secret);
        }
    }

    @Test
    void aCiphertextOnlyOpensForItsOwnUserAndNotAtAllOnceTheKeyIsShredded() {
        SessionApi.Session owner = sessions.signIn(email, STRONG_PASSWORD);
        twoFactor.setup(owner.accessToken(), STRONG_PASSWORD);
        String otherEmail = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(otherEmail);
        SessionApi.Session other = sessions.signIn(otherEmail, STRONG_PASSWORD);
        twoFactor.setup(other.accessToken(), STRONG_PASSWORD);
        UUID ownerId = UUID.fromString(owner.userId());
        byte[] ownersCiphertext = jdbc.queryForObject("select totp_secret_ciphertext from users where id = ?",
                byte[].class, ownerId);

        assertThatThrownBy(() -> decrypt(UUID.fromString(other.userId()), ownersCiphertext))
                .isInstanceOf(DecryptionFailedException.class);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(encryption.shred(ownerId)).isEqualTo(1));

        assertThat(jdbc.queryForObject("select octet_length(wrapped_dek) from user_encryption_keys where user_id = ?",
                Integer.class, ownerId)).isZero();
        assertThatThrownBy(() -> decrypt(ownerId, ownersCiphertext)).isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void loginWithTwoFactorReturnsOnlyAChallenge() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        twoFactor.enrol(token, STRONG_PASSWORD, clock);
        Integer successesBefore = successfulLogins();

        HttpResponse<String> response = login.login(email, STRONG_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.status", String.class)).isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat(body.read("$.session", Object.class)).isNull();
        assertThat(body.read("$.secondFactor.challengeId", String.class)).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.read("$.secondFactor.expiresIn", Integer.class)).isEqualTo(300);
        assertThat(body.<List<String>>read("$.secondFactor.methods")).containsExactly("TOTP", "BACKUP_CODE");
        assertThat(response.body()).doesNotContain("accessToken", "refreshToken");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(successfulLogins()).isEqualTo(successesBefore);
    }

    @Test
    void aValidTotpCodeCompletesTheLoginWithOtpInAmrForTheWholeSession() throws ParseException {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        TestAuthenticator phone = twoFactor.enrol(token, STRONG_PASSWORD, clock).authenticator();
        String challengeId = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));

        HttpResponse<String> response = twoFactor.secondFactor(challengeId, phone.nextCode());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        String accessToken = JsonPath.read(response.body(), "$.session.accessToken");
        assertThat(amr(accessToken)).containsExactly("pwd", "otp");
        assertThat(LoginApi.deviceSetCookie(response)).isPresent();
        String refreshToken = SessionApi.refreshToken(response);
        assertThat(sessions.me(accessToken).statusCode()).isEqualTo(200);

        HttpResponse<String> refreshed = sessions.refresh(refreshToken);
        assertThat(amr(SessionApi.accessTokenOfRefresh(refreshed))).containsExactly("pwd", "otp");
        assertThat(amr(token)).containsExactly("pwd");

        HttpResponse<String> reused = twoFactor.secondFactor(challengeId, phone.nextCode());
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(reused)).isEqualTo("login_challenge_expired");
    }

    @Test
    void aReplayedTotpCodeIsRefusedEvenInsideItsWindow() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        TestAuthenticator phone = twoFactor.enrol(token, STRONG_PASSWORD, clock).authenticator();
        String usedForConfirm = phone.currentCode();
        String challengeId = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));

        HttpResponse<String> replayed = twoFactor.secondFactor(challengeId, usedForConfirm);

        assertThat(replayed.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(replayed)).isEqualTo("invalid_second_factor");
        String fresh = phone.nextCode();
        assertThat(twoFactor.secondFactor(challengeId, fresh).statusCode()).isEqualTo(200);

        String nextChallenge = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));
        assertThat(twoFactor.secondFactor(nextChallenge, fresh).statusCode()).isEqualTo(401);
        assertThat(twoFactor.secondFactor(nextChallenge, usedForConfirm).statusCode()).isEqualTo(401);
        assertThat(failures("BAD_SECOND_FACTOR")).isEqualTo(3);
    }

    @Test
    void theSameCodeRacedOnTwoLoginsOpensExactlyOneSession() throws Exception {
        SessionApi.Session first = sessions.signIn(email, STRONG_PASSWORD);
        TestAuthenticator phone = twoFactor.enrol(first.accessToken(), STRONG_PASSWORD, clock).authenticator();
        UUID userId = UUID.fromString(first.userId());
        String laptop = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));
        String tablet = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));
        String code = phone.nextCode();
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> fromLaptop = pool.submit(() -> {
                start.await();
                return twoFactor.secondFactor(laptop, code);
            });
            Future<HttpResponse<String>> fromTablet = pool.submit(() -> {
                start.await();
                return twoFactor.secondFactor(tablet, code);
            });
            start.countDown();
            statuses = List.of(fromLaptop.get(30, TimeUnit.SECONDS).statusCode(),
                    fromTablet.get(30, TimeUnit.SECONDS).statusCode());
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        assertThat(jdbc.queryForObject("select count(*) from refresh_token_families "
                + "where user_id = ? and second_factor_verified", Integer.class, userId)).isOne();
    }

    @Test
    void aBackupCodeCompletesTheLoginOnceAndNeverWorksAgain() throws ParseException {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        List<String> backupCodes = twoFactor.enrol(token, STRONG_PASSWORD, clock).backupCodes();
        String challengeId = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));

        HttpResponse<String> response = twoFactor.secondFactor(challengeId,
                backupCodes.getFirst().toLowerCase().replace("-", " "));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String accessToken = JsonPath.read(response.body(), "$.session.accessToken");
        assertThat(amr(accessToken)).containsExactly("pwd", "otp");
        assertStatus(accessToken, true, false, 9);
        mailpit.awaitSubject(email, MailpitClient.BACKUP_CODE_USED_SUBJECT);
        assertThat(mailpit.latestText(email, MailpitClient.BACKUP_CODE_USED_SUBJECT))
                .contains("Backup codes left: 9").doesNotContain(backupCodes.getFirst());

        String nextChallenge = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));
        HttpResponse<String> again = twoFactor.secondFactor(nextChallenge, backupCodes.getFirst());
        assertThat(again.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(again)).isEqualTo("invalid_second_factor");
        assertThat(twoFactor.secondFactor(nextChallenge, backupCodes.get(1)).statusCode()).isEqualTo(200);
    }

    @Test
    void wrongCodesUseUpTheChallengeAndLockTheSecondStep() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        TwoFactorApi.Enrolment enrolment = twoFactor.enrol(token, STRONG_PASSWORD, clock);
        TestAuthenticator phone = enrolment.authenticator();
        String challengeId = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));

        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpResponse<String> wrong = twoFactor.secondFactor(challengeId, phone.wrongCode());
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(SessionApi.code(wrong)).isEqualTo("invalid_second_factor");
        }
        HttpResponse<String> sixth = twoFactor.secondFactor(challengeId, phone.currentCode());
        assertThat(sixth.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(sixth)).isEqualTo("login_challenge_expired");

        // A new challenge does not reset the code counter: it is locked now,
        // and a locked attempt spends nothing.
        String freshChallenge = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));
        HttpResponse<String> locked = twoFactor.secondFactor(freshChallenge, enrolment.backupCodes().getFirst());
        assertThat(locked.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(locked)).isEqualTo("invalid_second_factor");
        assertThat(failures("LOCKED")).isEqualTo(1);
        assertThat(failures("BAD_SECOND_FACTOR")).isEqualTo(5);
        assertStatus(token, true, false, 10);

        clock.advance(Duration.ofMinutes(2));
        assertThat(twoFactor.secondFactor(freshChallenge, phone.nextCode()).statusCode()).isEqualTo(200);
    }

    @Test
    void theChallengeExpiresAfterFiveMinutes() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        TestAuthenticator phone = twoFactor.enrol(token, STRONG_PASSWORD, clock).authenticator();
        String challengeId = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        HttpResponse<String> response = twoFactor.secondFactor(challengeId, phone.currentCode());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(response)).isEqualTo("login_challenge_expired");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void aMadeUpChallengeGetsTheSameAnswerAsAnExpiredOne() {
        HttpResponse<String> response = twoFactor.secondFactor("A".repeat(43), "123456");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(SessionApi.code(response)).isEqualTo("login_challenge_expired");
        assertThat(twoFactor.secondFactor("not-a-challenge", "123456").statusCode()).isEqualTo(400);
    }

    @Test
    void regeneratingBackupCodesKillsTheOldSet() {
        String token = sessions.signIn(email, STRONG_PASSWORD).accessToken();
        TwoFactorApi.Enrolment enrolment = twoFactor.enrol(token, STRONG_PASSWORD, clock);
        TestAuthenticator phone = enrolment.authenticator();

        HttpResponse<String> wrongPassword = twoFactor.regenerate(token, "not-the-password-at-all", phone.nextCode());
        assertThat(SessionApi.code(wrongPassword)).isEqualTo("reauthentication_failed");
        HttpResponse<String> wrongCode = twoFactor.regenerate(token, STRONG_PASSWORD, phone.wrongCode());
        assertThat(SessionApi.code(wrongCode)).isEqualTo("reauthentication_failed");

        HttpResponse<String> response = twoFactor.regenerate(token, STRONG_PASSWORD, phone.nextCode());

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> fresh = JsonPath.read(response.body(), "$.backupCodes");
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(enrolment.backupCodes());
        assertStatus(token, true, false, 10);
        mailpit.awaitSubject(email, MailpitClient.BACKUP_CODES_REGENERATED_SUBJECT);

        String challengeId = TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD));
        for (String old : enrolment.backupCodes().subList(0, 3)) {
            assertThat(twoFactor.secondFactor(challengeId, old).statusCode()).isEqualTo(401);
        }
        assertThat(twoFactor.secondFactor(challengeId, fresh.getFirst()).statusCode()).isEqualTo(200);
    }

    @Test
    void disablingNeedsThePasswordAndACodeAndEndsOtherSessions() {
        SessionApi.Session laptop = sessions.signIn(email, STRONG_PASSWORD);
        TestAuthenticator phone = twoFactor.enrol(laptop.accessToken(), STRONG_PASSWORD, clock).authenticator();
        HttpResponse<String> otherLogin = twoFactor.secondFactor(
                TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD)), phone.nextCode());
        String otherToken = JsonPath.read(otherLogin.body(), "$.session.accessToken");

        HttpResponse<String> wrongPassword = twoFactor.disable(laptop.accessToken(), "not-the-password-at-all",
                phone.nextCode());
        assertThat(wrongPassword.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(wrongPassword)).isEqualTo("reauthentication_failed");
        HttpResponse<String> wrongCode = twoFactor.disable(laptop.accessToken(), STRONG_PASSWORD, phone.wrongCode());
        assertThat(wrongCode.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(wrongCode)).isEqualTo("reauthentication_failed");
        assertThat(twoFactor.disable(laptop.accessToken(), STRONG_PASSWORD, "").statusCode()).isEqualTo(400);
        assertStatus(laptop.accessToken(), true, false, 10);

        HttpResponse<String> disabled = twoFactor.disable(laptop.accessToken(), STRONG_PASSWORD, phone.nextCode());

        assertThat(disabled.statusCode()).isEqualTo(204);
        assertStatus(laptop.accessToken(), false, false, 0);
        assertThat(sessions.me(otherToken).statusCode()).isEqualTo(401);
        assertThat(sessions.me(laptop.accessToken()).statusCode()).isEqualTo(200);
        UUID userId = UUID.fromString(laptop.userId());
        assertThat(jdbc.queryForObject("select totp_secret_ciphertext is null and totp_last_used_step is null "
                + "from users where id = ?", Boolean.class, userId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from totp_backup_codes where user_id = ?",
                Integer.class, userId)).isZero();
        mailpit.awaitSubject(email, MailpitClient.TWO_FACTOR_DISABLED_SUBJECT);
        assertThat(JsonPath.<String>read(login.login(email, STRONG_PASSWORD).body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
        assertThat(SessionApi.code(twoFactor.disable(laptop.accessToken(), STRONG_PASSWORD, "123456")))
                .isEqualTo("two_factor_not_enabled");
    }

    @Test
    void anAccountWithoutTwoFactorCannotBeMadeAdmin() {
        UUID userId = UUID.fromString(sessions.signIn(email, STRONG_PASSWORD).userId());

        assertThatThrownBy(() -> jdbc.update("update users set role = 'ADMIN' where id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_admin_has_two_factor");
    }

    @Test
    void adminRoutesNeedAnAdminWhoseSessionPassedASecondFactor() {
        SessionApi.Session passwordOnly = sessions.signIn(email, STRONG_PASSWORD);
        TestAuthenticator phone = twoFactor.enrol(passwordOnly.accessToken(), STRONG_PASSWORD, clock).authenticator();
        UUID adminId = UUID.fromString(passwordOnly.userId());
        jdbc.update("update users set role = 'ADMIN' where id = ?", adminId);
        String adminRoute = "/api/v1/admin/users/" + adminId;

        String adminWithoutOtp = SessionApi.accessTokenOfRefresh(sessions.refresh(passwordOnly.refreshToken()));
        assertThat(JsonPath.<String>read(sessions.me(adminWithoutOtp).body(), "$.role")).isEqualTo("ADMIN");
        HttpResponse<String> refused = http.get(adminRoute, "Authorization", "Bearer " + adminWithoutOtp);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));

        HttpResponse<String> adminLogin = twoFactor.secondFactor(
                TwoFactorApi.challengeId(login.login(email, STRONG_PASSWORD)), phone.nextCode());
        String adminWithOtp = JsonPath.read(adminLogin.body(), "$.session.accessToken");
        HttpResponse<String> allowed = http.get(adminRoute, "Authorization", "Bearer " + adminWithOtp);
        assertThat(allowed.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(allowed.body());
        assertThat(body.read("$.id", String.class)).isEqualTo(adminId.toString());
        assertThat(body.read("$.role", String.class)).isEqualTo("ADMIN");
        assertThat(body.read("$.twoFactorEnabled", Boolean.class)).isTrue();
        assertThat(allowed.body()).doesNotContain("password", "argon2", "totp", "secret");
        HttpResponse<String> unknown = http.get("/api/v1/admin/users/" + UUID.randomUUID(),
                "Authorization", "Bearer " + adminWithOtp);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(SessionApi.code(unknown)).isEqualTo("not_found");
        assertThat(http.get("/api/v1/admin/users/not-a-uuid", "Authorization", "Bearer " + adminWithOtp)
                .statusCode()).isEqualTo(400);

        HttpResponse<String> keepTwoFactor = twoFactor.disable(adminWithOtp, STRONG_PASSWORD, phone.nextCode());
        assertThat(keepTwoFactor.statusCode()).isEqualTo(403);
        assertThat(SessionApi.code(keepTwoFactor)).isEqualTo("two_factor_required_for_admin");

        jdbc.update("update users set role = 'USER' where id = ?", adminId);
        assertThat(http.get(adminRoute, "Authorization", "Bearer " + adminWithOtp).statusCode()).isEqualTo(403);
    }

    private void assertStatus(String accessToken, boolean enabled, boolean setupPending, int backupCodes) {
        HttpResponse<String> response = twoFactor.status(accessToken);
        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.enabled", Boolean.class)).isEqualTo(enabled);
        assertThat(body.read("$.setupPending", Boolean.class)).isEqualTo(setupPending);
        assertThat(body.read("$.backupCodesRemaining", Integer.class)).isEqualTo(backupCodes);
    }

    private byte[] decrypt(UUID userId, byte[] ciphertext) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User user = users.findById(userId).orElseThrow();
            return encryption.decrypt(user, EncryptedField.TOTP_SECRET, ciphertext);
        });
    }

    private Integer successfulLogins() {
        return jdbc.queryForObject("select count(*) from login_history where email_attempted = ? and success",
                Integer.class, email);
    }

    private Integer failures(String reason) {
        return jdbc.queryForObject("select count(*) from login_history where email_attempted = ? "
                + "and failure_reason = ?", Integer.class, email, reason);
    }

    private String revocation(String sessionId) {
        return jdbc.queryForObject("select revoked_reason from refresh_token_families where id = ?::uuid",
                String.class, sessionId);
    }

    private static List<String> amr(String accessToken) throws ParseException {
        return SignedJWT.parse(accessToken).getJWTClaimsSet().getStringListClaim("amr");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }
}
