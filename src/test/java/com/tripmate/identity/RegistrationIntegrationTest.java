package com.tripmate.identity;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.dto.VerificationChallengeResponse;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.bornYearsAgo;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static com.tripmate.identity.RegistrationApi.verificationId;
import static com.tripmate.support.StubBreachedPasswords.BREACHED_PASSWORD;
import static com.tripmate.support.StubBreachedPasswords.UNCHECKABLE_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class RegistrationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private RegistrationApi api;

    @BeforeEach
    void setUp() {
        api = new RegistrationApi(new LocalHttp(port), mailpit);
    }

    @Test
    void newAddressIsStoredWithArgon2idHashAndEmailedACode() {
        String email = uniqueEmail();

        HttpResponse<String> response = api.register(email);

        assertThat(response.statusCode()).isEqualTo(202);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.message", String.class)).isEqualTo(VerificationChallengeResponse.MESSAGE);
        assertThat(response.body()).doesNotContain(STRONG_PASSWORD);

        Map<String, Object> row = userRow(email);
        assertThat((String) row.get("password_hash"))
                .startsWith("$argon2id$v=19$m=19456,t=2,p=1$")
                .doesNotContain(STRONG_PASSWORD);
        assertThat(row.get("id_verification_tier")).isEqualTo("NONE");
        assertThat(row.get("email_verified_at")).isNull();
        assertThat(mailpit.awaitVerificationCode(email)).matches("\\d{6}");
    }

    @Test
    void verifiedDuplicateGetsTheSameResponseAsANewAddress() {
        String taken = uniqueEmail();
        api.registerVerified(taken);
        String originalHash = (String) userRow(taken).get("password_hash");

        HttpResponse<String> duplicate = api.register(taken, "a-different-password-77", bornYearsAgo(30));
        HttpResponse<String> fresh = api.register(uniqueEmail());

        assertThat(duplicate.statusCode()).isEqualTo(fresh.statusCode()).isEqualTo(202);
        assertThat(duplicate.headers().firstValue("Content-Type"))
                .isEqualTo(fresh.headers().firstValue("Content-Type"));
        assertThat(withoutVerificationId(duplicate)).isEqualTo(withoutVerificationId(fresh));

        UUID decoy = verificationId(duplicate);
        assertThat(jdbc.queryForObject("select count(*) from otp_codes where id = ?", Long.class, decoy)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Long.class, taken)).isOne();
        assertThat(userRow(taken).get("password_hash")).isEqualTo(originalHash);
        mailpit.awaitSubject(taken, MailpitClient.EXISTING_ACCOUNT_SUBJECT);
    }

    @Test
    void duplicateDetectionIgnoresCase() {
        String email = uniqueEmail();
        api.registerVerified(email);

        assertThat(api.register(email.toUpperCase()).statusCode()).isEqualTo(202);

        assertThat(jdbc.queryForObject(
                "select count(*) from users where lower(email::text) = lower(?)", Long.class, email)).isOne();
    }

    @Test
    void reRegisteringAnUnverifiedAddressReplacesThePasswordAndRetiresTheOldCode() {
        String email = uniqueEmail();
        UUID firstId = verificationId(api.register(email, STRONG_PASSWORD, bornYearsAgo(25)));
        String firstCode = mailpit.awaitVerificationCode(email, 1);

        UUID secondId = verificationId(api.register(email, "second-owner-password-42", bornYearsAgo(25)));
        String secondCode = mailpit.awaitVerificationCode(email, 2);

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(api.verify(firstId, firstCode).statusCode()).isEqualTo(400);
        assertThat(passwordEncoder.matches("second-owner-password-42", (String) userRow(email).get("password_hash")))
                .isTrue();
        assertThat(api.verify(secondId, secondCode).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Long.class, email)).isOne();
    }

    @Test
    void reRegistrationRetiresTheOldCodeByExpiryWithoutSpendingItsAttempts() {
        String email = uniqueEmail();
        UUID firstId = verificationId(api.register(email, STRONG_PASSWORD, bornYearsAgo(25)));
        String firstCode = mailpit.awaitVerificationCode(email, 1);
        UUID secondId = verificationId(api.register(email, "second-owner-password-42", bornYearsAgo(25)));

        // The correct code under the right ID: the only thing that can reject
        // it is the expiry that superseding moved back.
        assertThat(api.verify(firstId, firstCode).statusCode()).isEqualTo(400);

        Map<String, Object> first = jdbc.queryForMap(
                "select expires_at, attempt_count, consumed_at from otp_codes where id = ?", firstId);
        assertThat(jdbc.queryForObject("""
                select old.expires_at <= new.created_at
                from otp_codes old, otp_codes new
                where old.id = ? and new.id = ?""", Boolean.class, firstId, secondId)).isTrue();
        assertThat(((Number) first.get("attempt_count")).intValue()).isZero();
        assertThat(first.get("consumed_at")).isNull();
        assertThat(userRow(email).get("email_verified_at")).isNull();
    }

    @Test
    void underEighteenIsRejectedWithAFieldErrorAndNothingIsStored() {
        String email = uniqueEmail();

        HttpResponse<String> response = api.register(email, STRONG_PASSWORD, bornYearsAgo(18).plusDays(1));

        assertThat(response.statusCode()).isEqualTo(400);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.read("$.code", String.class)).isEqualTo("underage");
        assertThat(body.read("$.errors[0].field", String.class)).isEqualTo("dateOfBirth");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Long.class, email)).isZero();
    }

    @Test
    void eighteenthBirthdayTodayIsAcceptedByServiceAndDatabaseAlike() {
        String email = uniqueEmail();

        assertThat(api.register(email, STRONG_PASSWORD, bornYearsAgo(18)).statusCode()).isEqualTo(202);
        assertThat(userRow(email)).isNotEmpty();
    }

    @Test
    void databaseRejectsAnUnderageRowThatBypassesTheService() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (email, password_hash, full_name, date_of_birth, gender)
                values (?, 'x', 'Direct Insert', ?, 'MALE')""", uniqueEmail(), bornYearsAgo(17)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_adult");
    }

    @Test
    void shortPasswordIsRejected() {
        HttpResponse<String> response = api.register(uniqueEmail(), "short-pw", bornYearsAgo(25));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.errors[0].field")).isEqualTo("password");
        assertThat(response.body()).doesNotContain("short-pw");
    }

    @Test
    void breachedPasswordIsRejected() {
        HttpResponse<String> response = api.register(uniqueEmail(), BREACHED_PASSWORD, bornYearsAgo(25));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("password_breached");
        assertThat(JsonPath.<String>read(response.body(), "$.errors[0].field")).isEqualTo("password");
    }

    @Test
    void passwordBuiltFromTheEmailAddressIsRejected() {
        String email = "kavya.iyer." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String password = email.substring(0, email.indexOf('@')) + "!!";

        HttpResponse<String> response = api.register(email, password, bornYearsAgo(25));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("password_contains_personal_info");
    }

    @Test
    void unreachableBreachCorpusDoesNotBlockRegistration() {
        assertThat(api.register(uniqueEmail(), UNCHECKABLE_PASSWORD, bornYearsAgo(25)).statusCode()).isEqualTo(202);
    }

    @Test
    void rejectionsLookTheSameForTakenAndFreeAddresses() {
        String taken = uniqueEmail();
        api.registerVerified(taken);

        HttpResponse<String> forTaken = api.register(taken, BREACHED_PASSWORD, bornYearsAgo(25));
        HttpResponse<String> forFree = api.register(uniqueEmail(), BREACHED_PASSWORD, bornYearsAgo(25));

        assertThat(forTaken.statusCode()).isEqualTo(forFree.statusCode()).isEqualTo(400);
        assertThat(withoutCorrelationId(forTaken)).isEqualTo(withoutCorrelationId(forFree));
    }

    @Test
    void routesOtherThanTheOpenedPostsStayClosed() {
        LocalHttp http = new LocalHttp(port);

        assertThat(http.get(RegistrationApi.REGISTER).statusCode()).isEqualTo(401);
        // The CSRF filter runs before authorization, so an anonymous POST to
        // any route not exempted from CSRF is refused with 403 before the
        // 401 check is reached. Either way, no handler runs.
        assertThat(http.postJson("/api/v1/auth/login", "{}").statusCode()).isEqualTo(403);
    }

    private Map<String, Object> userRow(String email) {
        return jdbc.queryForMap("select * from users where email = ? and deleted_at is null", email);
    }

    private static Map<String, Object> withoutVerificationId(HttpResponse<String> response) {
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        assertThat(body.get("verificationId")).asString().matches("[0-9a-f-]{36}");
        body.remove("verificationId");
        return body;
    }

    private static Map<String, Object> withoutCorrelationId(HttpResponse<String> response) {
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        body.remove("correlationId");
        return body;
    }
}
