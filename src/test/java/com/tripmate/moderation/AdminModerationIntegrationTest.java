package com.tripmate.moderation;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.LoginApi;
import com.tripmate.identity.RegistrationApi;
import com.tripmate.identity.SessionApi;
import com.tripmate.identity.TwoFactorApi;
import com.tripmate.settlement.SettlementApi.Crew;
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

import java.net.http.HttpResponse;
import java.util.List;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The moderator's side of reports: the queue with details opened, closing a
 * report with or without a suspension, lifting it, and the audit trail. Who
 * may call these routes at all is the authorization matrix's job.
 */
@IntegrationTest
class AdminModerationIntegrationTest {

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
        String email = RegistrationApi.uniqueEmail();
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
    void aModeratorReadsTheDetailsClosesTheReportAndSuspendsThenReinstates() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        HttpResponse<String> filed = crew.companion(0).http().postJson("/api/v1/reports", """
                {"tripId":"%s","userId":"%s","reason":"HARASSMENT","details":"Kept messaging at night"}"""
                .formatted(crew.tripId(), crew.host().userId()), "Authorization", "Bearer " + crew.companion(0).token());
        assertThat(filed.statusCode()).as(filed.body()).isEqualTo(201);
        String reportId = JsonPath.read(filed.body(), "$.id");

        String queue = admin("GET", "/api/v1/admin/reports?size=50&page=0", null).body();
        int last = JsonPath.<Integer>read(queue, "$.totalPages") - 1;
        String page = admin("GET", "/api/v1/admin/reports?size=50&page=" + last, null).body();
        assertThat(JsonPath.<List<String>>read(page, "$.items[?(@.id == '" + reportId + "')].details"))
                .containsExactly("Kept messaging at night");
        assertThat(JsonPath.<List<String>>read(page, "$.items[?(@.id == '" + reportId + "')].reported.userId"))
                .containsExactly(crew.host().userId());

        HttpResponse<String> decided = admin("POST", "/api/v1/admin/reports/" + reportId + "/decision",
                "{\"decision\":\"ACTIONED\",\"suspend\":true}");
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(204);
        assertThat(status(crew.host().userId())).isEqualTo("SUSPENDED");
        assertThat(admin("POST", "/api/v1/admin/reports/" + reportId + "/decision", "{\"decision\":\"DISMISSED\"}")
                .body()).contains("\"report_closed\"");

        assertThat(admin("POST", "/api/v1/admin/users/" + crew.host().userId() + "/reinstate", null).statusCode())
                .isEqualTo(204);
        assertThat(status(crew.host().userId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("select event_type from audit_log where entity_id in (?::uuid, ?::uuid) "
                + "order by sequence_no", String.class, reportId, crew.host().userId()))
                .containsExactly("REPORT_FILED", "REPORT_CLOSED", "ACCOUNT_SUSPENDED", "ACCOUNT_REINSTATED");
    }

    @Test
    void aModeratorCannotActOnThemselves() {
        assertThat(admin("POST", "/api/v1/admin/users/" + adminId + "/suspend", null).body())
                .contains("\"cannot_moderate_self\"");
        assertThat(status(adminId)).isEqualTo("ACTIVE");
        assertThat(admin("POST", "/api/v1/admin/reports/" + java.util.UUID.randomUUID() + "/decision",
                "{\"decision\":\"DISMISSED\"}").statusCode()).isEqualTo(404);
        assertThat(admin("POST", "/api/v1/admin/reports/" + java.util.UUID.randomUUID() + "/decision",
                "{\"decision\":\"OPEN\"}").body()).contains("\"invalid_decision\"");
    }

    private HttpResponse<String> admin(String method, String path, String json) {
        String[] auth = {"Authorization", "Bearer " + adminToken};
        return switch (method) {
            case "GET" -> adminHttp.get(path, auth);
            default -> json == null ? adminHttp.post(path, auth) : adminHttp.postJson(path, json, auth);
        };
    }

    private String status(String userId) {
        return jdbc.queryForObject("select account_status from users where id = ?::uuid", String.class, userId);
    }
}
