package com.tripmate.compliance;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.compliance.service.DataRequestJob;
import com.tripmate.identity.LoginApi;
import com.tripmate.identity.VerificationApi;
import com.tripmate.platform.audit.AuditIntegrity;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.Traveller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit chain and its integrity check, consent records, data export and
 * account deletion, and that no email or phone number reaches the logs.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ComplianceIntegrationTest {

    private static final String REQUESTS = "/api/v1/users/me/data-requests";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditIntegrity integrity;

    @Autowired
    private DataRequestJob job;

    @Test
    void theAuditChainVerifiesAndAnEditAnywhereIsCaught() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        put(traveller, "/api/v1/users/me/consents", "{\"type\":\"MARKETING\",\"granted\":true}");
        put(traveller, "/api/v1/users/me/consents", "{\"type\":\"MARKETING\",\"granted\":false}");

        assertThat(integrity.check().intact()).isTrue();
        assertThatThrownBy(() -> jdbc.update("update audit_log set metadata = '{}' where entity_id = ?::uuid",
                traveller.userId())).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where entity_id = ?::uuid", traveller.userId()))
                .hasStackTraceContaining("append-only");

        Map<String, Object> row = jdbc.queryForMap("select sequence_no, metadata::text as metadata from audit_log "
                + "where entity_id = ?::uuid order by sequence_no limit 1", traveller.userId());
        jdbc.execute("alter table audit_log disable trigger trg_audit_log_append_only");
        try {
            jdbc.update("update audit_log set metadata = '{\"granted\":\"false\"}' where sequence_no = ?",
                    row.get("sequence_no"));
            AuditIntegrity.CheckResult tampered = integrity.check();
            assertThat(tampered.intact()).isFalse();
            assertThat(tampered.firstBrokenSeq()).isEqualTo(row.get("sequence_no"));
        } finally {
            jdbc.update("update audit_log set metadata = ?::jsonb where sequence_no = ?", row.get("metadata"),
                    row.get("sequence_no"));
            jdbc.execute("alter table audit_log enable trigger trg_audit_log_append_only");
        }
        assertThat(integrity.check().intact()).as("restored").isTrue();
    }

    @Test
    void signingUpRecordsTheTermsAndOnlyOptionalConsentsCanBeWithdrawn() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        String consents = get(traveller, "/api/v1/users/me/consents").body();
        assertThat(JsonPath.<List<Boolean>>read(consents, "$.items[?(@.type == 'TOS')].granted")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(consents, "$.items[?(@.type == 'MARKETING')].granted"))
                .containsExactly(false);

        HttpResponse<String> tos = put(traveller, "/api/v1/users/me/consents", "{\"type\":\"TOS\",\"granted\":false}");
        assertThat(tos.statusCode()).isEqualTo(400);
        assertThat(tos.body()).contains("\"consent_not_optional\"");
        HttpResponse<String> marketing = put(traveller, "/api/v1/users/me/consents", "{\"type\":\"MARKETING\",\"granted\":true}");
        assertThat(JsonPath.<List<Boolean>>read(marketing.body(), "$.items[?(@.type == 'MARKETING')].granted"))
                .containsExactly(true);
        assertThatThrownBy(() -> jdbc.update("update consent_records set granted = false where user_id = ?::uuid",
                traveller.userId())).hasStackTraceContaining("append-only");
    }

    @Test
    void anExportIsTheOwnersOnlyAndHoldsTheirDataButNoSecrets() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        Traveller owner = crew.host();
        new com.tripmate.chat.ChatApi(owner.http()).say(owner.token(),
                new com.tripmate.chat.ChatApi(owner.http()).onlyConversationId(owner.token()), "See you at the station");

        HttpResponse<String> asked = post(owner, REQUESTS, "{\"type\":\"EXPORT\"}");
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        assertThat(post(owner, REQUESTS, "{\"type\":\"EXPORT\"}").body()).contains("\"data_request_pending\"");
        String id = JsonPath.read(asked.body(), "$.id");
        assertThat(get(owner, REQUESTS + "/" + id + "/export").body()).contains("\"export_not_ready\"");

        job.sweep();

        HttpResponse<String> export = get(owner, REQUESTS + "/" + id + "/export");
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("Content-Disposition")).hasValueSatisfying(value ->
                assertThat(value).contains("attachment"));
        assertThat(JsonPath.<String>read(export.body(), "$.account.profile[0].email")).isEqualTo(owner.email());
        assertThat(JsonPath.<List<String>>read(export.body(), "$.messages.sent[*].body"))
                .containsExactly("See you at the station");
        assertThat(JsonPath.<List<Object>>read(export.body(), "$.trips.hosted")).hasSize(1);
        assertThat(export.body()).doesNotContain("password", "totp_secret", "blind_index", "ciphertext");
        byte[] sealed = jdbc.queryForObject("select export_ciphertext from data_requests where id = ?::uuid",
                byte[].class, id);
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(owner.email());

        HttpResponse<String> other = get(crew.companion(0), REQUESTS + "/" + id + "/export");
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(other.body()).contains("\"not_found\"");
    }

    @Test
    void deletingAnAccountNeedsThePasswordThenScrubsAndShredsIt() {
        Traveller traveller = Traveller.signUp(port, mailpit);
        new VerificationApi(traveller.http(), mailpit).verifyPhone(traveller.email(), traveller.token());

        HttpResponse<String> wrong = post(traveller, REQUESTS, "{\"type\":\"DELETION\",\"password\":\"not-it\"}");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("\"reauthentication_failed\"");
        HttpResponse<String> asked = post(traveller, REQUESTS,
                "{\"type\":\"DELETION\",\"password\":\"" + STRONG_PASSWORD + "\"}");
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);

        job.sweep();

        Map<String, Object> user = jdbc.queryForMap("select email::text as email, full_name, account_status, "
                + "deleted_at, phone_ciphertext, id_verification_tier from users where id = ?::uuid", traveller.userId());
        assertThat(user.get("email").toString()).startsWith("deleted-").endsWith("@deleted.invalid");
        assertThat(user).containsEntry("full_name", "Deleted user").containsEntry("account_status", "DELETED")
                .containsEntry("phone_ciphertext", null).containsEntry("id_verification_tier", "NONE");
        assertThat(user.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from user_encryption_keys where user_id = ?::uuid "
                + "and active", Long.class, traveller.userId())).as("every data key shredded").isZero();
        assertThat(get(traveller, "/api/v1/users/me").statusCode()).as("sessions ended").isEqualTo(401);
        assertThat(new LoginApi(traveller.http()).login(traveller.email(), STRONG_PASSWORD).statusCode())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where event_type = 'ACCOUNT_DELETED' "
                + "and entity_id = ?::uuid", Long.class, traveller.userId())).isEqualTo(1);
    }

    @Test
    void noEmailOrPhoneNumberReachesTheLogs(CapturedOutput output) {
        Traveller traveller = Traveller.signUp(port, mailpit);
        String phone = VerificationApi.uniquePhone();
        new VerificationApi(traveller.http(), mailpit).sendPhoneCode(traveller.token(), phone);
        new LoginApi(traveller.http()).login(traveller.email(), "wrong-password-entirely");
        LoggerFactory.getLogger(ComplianceIntegrationTest.class)
                .warn("Diagnostic for {} on {}", traveller.email(), phone);

        String digits = phone.replaceAll("\\D", "");
        assertThat(output.getOut()).contains("Diagnostic for")
                .doesNotContain(traveller.email())
                .doesNotContain(digits.substring(digits.length() - 10));
    }

    private HttpResponse<String> get(Traveller who, String path) {
        return who.http().get(path, "Authorization", "Bearer " + who.token());
    }

    private HttpResponse<String> post(Traveller who, String path, String json) {
        return who.http().postJson(path, json, "Authorization", "Bearer " + who.token());
    }

    private HttpResponse<String> put(Traveller who, String path, String json) {
        return who.http().putJson(path, json, "Authorization", "Bearer " + who.token());
    }
}
