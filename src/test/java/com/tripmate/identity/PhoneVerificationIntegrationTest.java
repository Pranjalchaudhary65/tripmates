package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.phone.PhoneBlindIndex;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class PhoneVerificationIntegrationTest {

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
    private PhoneBlindIndex blindIndex;

    @Autowired
    private UserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private VerificationApi verification;

    private String email;

    private SessionApi.Session session;

    @BeforeEach
    void setUp() {
        LocalHttp http = new LocalHttp(port);
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
    void aConfirmedCodeRaisesTheTierAndRecordsWhyInTheOutbox() {
        String number = VerificationApi.uniquePhone();
        VerificationApi.PendingPhone pending = verification.requestPhoneCode(email, token(), number, 0);

        HttpResponse<String> confirmed = verification.confirmPhone(token(), pending.verificationId(), pending.code());

        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(confirmed.body(), "$.tier")).isEqualTo("PHONE");
        assertThat(JsonPath.<Boolean>read(confirmed.body(), "$.phone.verified")).isTrue();
        assertThat(JsonPath.<String>read(confirmed.body(), "$.phone.maskedNumber"))
                .isEqualTo("+91 ••••••• " + number.substring(number.length() - 3));
        assertThat(jdbc.queryForObject("""
                select count(*) from identity_verifications
                where user_id = ?::uuid and tier = 'PHONE' and status = 'APPROVED'""", Integer.class, userId()))
                .isEqualTo(1);
        List<Map<String, Object>> events = jdbc.queryForList("""
                select payload::text as payload from outbox_events
                where aggregate_id = ?::uuid and event_type = 'VERIFICATION_TIER_CHANGE'
                order by created_at""", userId());
        assertThat(events).extracting(row -> (String) row.get("payload"))
                .anySatisfy(payload -> assertThat(payload).contains("\"from\": \"EMAIL\"", "\"to\": \"PHONE\""));
    }

    @Test
    void verifyingTheEmailWasRecordedAsTheFirstTierChange() {
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                where aggregate_id = ?::uuid and event_type = 'VERIFICATION_TIER_CHANGE'
                  and payload ->> 'from' = 'NONE' and payload ->> 'to' = 'EMAIL'""", Integer.class, userId()))
                .isEqualTo(1);
    }

    @Test
    void theNumberIsStoredOnlyEncryptedWithAKeyedBlindIndex() {
        String number = verification.verifyPhone(email, token());

        Map<String, Object> row = jdbc.queryForMap(
                "select phone_ciphertext, phone_blind_index from users where id = ?::uuid", userId());
        byte[] ciphertext = (byte[]) row.get("phone_ciphertext");
        String digits = number.substring(1);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(digits);
        assertThat(row.get("phone_blind_index")).isEqualTo(blindIndex.of(number));

        String decrypted = new TransactionTemplate(transactionManager).execute(status -> new String(
                encryption.decrypt(users.findById(UUID.fromString(userId())).orElseThrow(), EncryptedField.PHONE,
                        ciphertext), StandardCharsets.UTF_8));
        assertThat(decrypted).isEqualTo(number);
    }

    @Test
    void twoSpellingsOfOneNumberGiveOneIndex() {
        HttpResponse<String> spaced = verification.sendPhoneCode(token(), "098765 43210");
        assertThat(spaced.statusCode()).as(spaced.body()).isEqualTo(202);
        assertThat(jdbc.queryForObject("select phone_blind_index from users where id = ?::uuid", String.class,
                userId())).isEqualTo(blindIndex.of("+919876543210"));
    }

    @Test
    void aWrongCodeIsRefusedAndCountedSoTheRightOneStopsWorkingAfterFiveGuesses() {
        VerificationApi.PendingPhone pending = verification.requestPhoneCode(email, token(),
                VerificationApi.uniquePhone(), 0);
        String wrong = pending.code().equals("000000") ? "111111" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> refused = verification.confirmPhone(token(), pending.verificationId(), wrong);
            assertThat(refused.statusCode()).isEqualTo(400);
            assertThat(SessionApi.code(refused)).isEqualTo("invalid_phone_code");
        }
        HttpResponse<String> right = verification.confirmPhone(token(), pending.verificationId(), pending.code());

        assertThat(right.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(right)).isEqualTo("invalid_phone_code");
        assertTier("EMAIL");
    }

    @Test
    void anExpiredCodeIsRefused() {
        VerificationApi.PendingPhone pending = verification.requestPhoneCode(email, token(),
                VerificationApi.uniquePhone(), 0);
        clock.advance(Duration.ofMinutes(11));

        HttpResponse<String> late = verification.confirmPhone(token(), pending.verificationId(), pending.code());

        assertThat(late.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(late)).isEqualTo("invalid_phone_code");
    }

    @Test
    void someoneElsesCodeCannotBeConfirmedEvenWithTheRightDigits() {
        VerificationApi.PendingPhone victims = verification.requestPhoneCode(email, token(),
                VerificationApi.uniquePhone(), 0);
        LocalHttp otherHttp = new LocalHttp(port);
        String otherEmail = uniqueEmail();
        new RegistrationApi(otherHttp, mailpit).registerVerified(otherEmail);
        SessionApi.Session other = new SessionApi(otherHttp).signIn(otherEmail, STRONG_PASSWORD);

        HttpResponse<String> stolen = new VerificationApi(otherHttp, mailpit)
                .confirmPhone(other.accessToken(), victims.verificationId(), victims.code());

        assertThat(stolen.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(stolen)).isEqualTo("invalid_phone_code");
        HttpResponse<String> owner = verification.confirmPhone(token(), victims.verificationId(), victims.code());
        assertThat(owner.statusCode()).as("the owner's code was not spent by the attempt").isEqualTo(200);
    }

    @Test
    void anEmailVerificationCodeIsNotAPhoneCode() {
        LocalHttp http = new LocalHttp(port);
        String otherEmail = uniqueEmail();
        RegistrationApi registration = new RegistrationApi(http, mailpit);
        HttpResponse<String> registered = registration.register(otherEmail);
        String emailCode = mailpit.awaitVerificationCode(otherEmail);

        HttpResponse<String> response = verification.confirmPhone(token(),
                RegistrationApi.verificationId(registered).toString(), emailCode);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(response)).isEqualTo("invalid_phone_code");
    }

    @Test
    void aNumberVerifiedOnAnotherAccountIsReportedOnlyAfterTheRightCode() {
        String number = verification.verifyPhone(email, token());
        LocalHttp otherHttp = new LocalHttp(port);
        String otherEmail = uniqueEmail();
        new RegistrationApi(otherHttp, mailpit).registerVerified(otherEmail);
        SessionApi.Session other = new SessionApi(otherHttp).signIn(otherEmail, STRONG_PASSWORD);
        VerificationApi otherVerification = new VerificationApi(otherHttp, mailpit);

        VerificationApi.PendingPhone pending = otherVerification.requestPhoneCode(otherEmail, other.accessToken(),
                number, 0);
        HttpResponse<String> wrong = otherVerification.confirmPhone(other.accessToken(), pending.verificationId(),
                pending.code().equals("000000") ? "111111" : "000000");
        HttpResponse<String> right = otherVerification.confirmPhone(other.accessToken(), pending.verificationId(),
                pending.code());

        assertThat(SessionApi.code(wrong)).as("a guess learns nothing about the number")
                .isEqualTo("invalid_phone_code");
        assertThat(right.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(right)).isEqualTo("phone_in_use");
        assertThat(jdbc.queryForObject("select id_verification_tier from users where id = ?::uuid", String.class,
                other.userId())).isEqualTo("EMAIL");
    }

    @Test
    void aSecondCodeWithinTheCooldownIsRefusedWithRetryAfter() {
        verification.requestPhoneCode(email, token(), VerificationApi.uniquePhone(), 0);

        HttpResponse<String> again = verification.sendPhoneCode(token(), VerificationApi.uniquePhone());

        assertThat(again.statusCode()).isEqualTo(429);
        assertThat(SessionApi.code(again)).isEqualTo("phone_code_cooldown");
        assertThat(again.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void aNewCodeSupersedesTheOldOne() {
        VerificationApi.PendingPhone first = verification.requestPhoneCode(email, token(),
                VerificationApi.uniquePhone(), 0);
        clock.advance(Duration.ofSeconds(61));
        VerificationApi.PendingPhone second = verification.requestPhoneCode(email, token(),
                VerificationApi.uniquePhone(), 1);

        HttpResponse<String> old = verification.confirmPhone(token(), first.verificationId(), first.code());
        HttpResponse<String> current = verification.confirmPhone(token(), second.verificationId(), second.code());

        assertThat(old.statusCode()).isEqualTo(400);
        assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
    }

    @Test
    void theDailyCapStopsCodeNumberSix() {
        for (int sent = 0; sent < 5; sent++) {
            HttpResponse<String> response = verification.sendPhoneCode(token(), VerificationApi.uniquePhone());
            assertThat(response.statusCode()).as("code %d: %s", sent + 1, response.body()).isEqualTo(202);
            clock.advance(Duration.ofSeconds(61));
        }

        HttpResponse<String> sixth = verification.sendPhoneCode(token(), VerificationApi.uniquePhone());

        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(SessionApi.code(sixth)).isEqualTo("phone_code_daily_limit");
        assertThat(sixth.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void aMalformedNumberIsRefusedAgainstItsField() {
        HttpResponse<String> response = verification.sendPhoneCode(token(), "12345");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(response)).isEqualTo("invalid_phone_number");
        assertThat(JsonPath.<String>read(response.body(), "$.errors[0].field")).isEqualTo("phoneNumber");
    }

    @Test
    void aVerifiedNumberCannotBeSwappedHere() {
        verification.verifyPhone(email, token());

        HttpResponse<String> again = verification.sendPhoneCode(token(), VerificationApi.uniquePhone());

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(again)).isEqualTo("phone_already_verified");
    }

    @Test
    void theCodeEmailShowsOnlyTheMaskedNumber() {
        String number = VerificationApi.uniquePhone();
        verification.requestPhoneCode(email, token(), number, 0);

        String text = mailpit.latestText(email, MailpitClient.PHONE_CODE_SUBJECT);

        assertThat(text).doesNotContain(number.substring(3));
        assertThat(text).contains(number.substring(number.length() - 3));
    }

    private String token() {
        return session.accessToken();
    }

    private String userId() {
        return session.userId();
    }

    private void assertTier(String tier) {
        assertThat(jdbc.queryForObject("select id_verification_tier from users where id = ?::uuid", String.class,
                userId())).isEqualTo(tier);
    }
}
