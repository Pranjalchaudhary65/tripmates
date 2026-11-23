package com.tripmate.identity;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.service.VouchService;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.util.UUID;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static com.tripmate.identity.RegistrationApi.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class VouchIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VouchService vouchService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Account voucher;

    @BeforeEach
    void setUp() {
        voucher = account();
    }

    @Test
    void anAccountBelowSelfieMatchCannotVouch() {
        Account friend = account();

        HttpResponse<String> response = voucher.api().vouch(voucher.token(), friend.userId());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(response)).isEqualTo("vouch_requires_selfie_match");
        assertThat(JsonPath.<Boolean>read(voucher.api().vouches(voucher.token()).body(), "$.canVouch")).isFalse();
    }

    @Test
    void aVerifiedAccountVouchesAndTheTrustScoreHearsOfIt() {
        voucher.reachSelfieMatch();
        Account friend = account();

        HttpResponse<String> response = voucher.api().vouch(voucher.token(), friend.userId());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.vouchedUserId")).isEqualTo(friend.userId());
        assertThat(JsonPath.<String>read(response.body(), "$.vouchedFirstName")).isEqualTo("Asha");
        assertThat(response.body()).doesNotContain(friend.email(), "Verma");
        assertThat(JsonPath.<Integer>read(friend.api().vouches(friend.token()).body(), "$.receivedCount"))
                .isEqualTo(1);
        assertThat(outboxEvents(friend.userId(), "VOUCH_GIVEN")).isEqualTo(1);
    }

    @Test
    void vouchingForYourselfTwiceOrForNobodyIsRefused() {
        voucher.reachSelfieMatch();
        Account friend = account();
        voucher.api().vouch(voucher.token(), friend.userId());

        HttpResponse<String> self = voucher.api().vouch(voucher.token(), voucher.userId());
        HttpResponse<String> twice = voucher.api().vouch(voucher.token(), friend.userId());
        HttpResponse<String> nobody = voucher.api().vouch(voucher.token(), UUID.randomUUID().toString());

        assertThat(self.statusCode()).isEqualTo(400);
        assertThat(SessionApi.code(self)).isEqualTo("cannot_vouch_for_self");
        assertThat(twice.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(twice)).isEqualTo("already_vouching");
        assertThat(nobody.statusCode()).isEqualTo(404);
        assertThat(SessionApi.code(nobody)).isEqualTo("not_found");
    }

    @Test
    void theSixthActiveVouchIsRefusedUntilOneIsWithdrawn() {
        voucher.reachSelfieMatch();
        String first = null;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> given = voucher.api().vouch(voucher.token(), account().userId());
            assertThat(given.statusCode()).as(given.body()).isEqualTo(201);
            if (first == null) {
                first = JsonPath.read(given.body(), "$.id");
            }
        }
        String sixth = account().userId();

        HttpResponse<String> refused = voucher.api().vouch(voucher.token(), sixth);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(SessionApi.code(refused)).isEqualTo("vouch_limit_reached");

        assertThat(voucher.api().revokeVouch(voucher.token(), first).statusCode()).isEqualTo(204);
        assertThat(voucher.api().vouch(voucher.token(), sixth).statusCode()).isEqualTo(201);
    }

    @Test
    void onlyTheVoucherCanWithdrawAVouchAndOnlyOnce() {
        voucher.reachSelfieMatch();
        Account friend = account();
        String vouchId = JsonPath.read(voucher.api().vouch(voucher.token(), friend.userId()).body(), "$.id");

        HttpResponse<String> byFriend = friend.api().revokeVouch(friend.token(), vouchId);
        assertThat(byFriend.statusCode()).isEqualTo(404);
        assertThat(SessionApi.code(byFriend)).isEqualTo("not_found");

        assertThat(voucher.api().revokeVouch(voucher.token(), vouchId).statusCode()).isEqualTo(204);
        assertThat(voucher.api().revokeVouch(voucher.token(), vouchId).statusCode()).isEqualTo(404);
        assertThat(outboxEvents(friend.userId(), "VOUCH_REVOKED")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(friend.api().vouches(friend.token()).body(), "$.receivedCount"))
                .isZero();
    }

    @Test
    void vouchingAgainAfterWithdrawingReopensTheSameVouch() {
        voucher.reachSelfieMatch();
        Account friend = account();
        String vouchId = JsonPath.read(voucher.api().vouch(voucher.token(), friend.userId()).body(), "$.id");
        voucher.api().revokeVouch(voucher.token(), vouchId);

        HttpResponse<String> again = voucher.api().vouch(voucher.token(), friend.userId());

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(again.body(), "$.id")).isEqualTo(vouchId);
    }

    @Test
    void aSuspensionPenalisesEveryActiveVoucherOfTheSuspendedAccount() {
        voucher.reachSelfieMatch();
        Account other = account();
        other.reachSelfieMatch();
        Account suspended = account();
        voucher.api().vouch(voucher.token(), suspended.userId());
        String withdrawn = JsonPath.read(other.api().vouch(other.token(), suspended.userId()).body(), "$.id");
        other.api().revokeVouch(other.token(), withdrawn);

        Integer penalised = new TransactionTemplate(transactionManager)
                .execute(status -> vouchService.penalizeVouchersOf(UUID.fromString(suspended.userId())));

        assertThat(penalised).isEqualTo(1);
        assertThat(outboxEvents(voucher.userId(), "VOUCH_PENALIZED")).isEqualTo(1);
        assertThat(outboxEvents(other.userId(), "VOUCH_PENALIZED")).as("a withdrawn vouch costs nothing").isZero();
    }

    private int outboxEvents(String aggregateId, String type) {
        Integer count = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = ?",
                Integer.class, aggregateId, type);
        return count == null ? 0 : count;
    }

    private Account account() {
        LocalHttp http = new LocalHttp(port);
        String email = uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        return new Account(email, new SessionApi(http).signIn(email, STRONG_PASSWORD),
                new VerificationApi(http, mailpit));
    }

    private record Account(String email, SessionApi.Session session, VerificationApi api) {

        String token() {
            return session.accessToken();
        }

        String userId() {
            return session.userId();
        }

        void reachSelfieMatch() {
            api.reachSelfieMatch(email, token());
        }
    }
}
