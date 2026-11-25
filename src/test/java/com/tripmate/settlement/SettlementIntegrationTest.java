package com.tripmate.settlement;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.chat.ChatApi;
import com.tripmate.settlement.SettlementApi.Crew;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.Traveller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.tripmate.settlement.SettlementApi.equalSplit;
import static com.tripmate.settlement.SettlementApi.exactSplit;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SettlementIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anExpenseBecomesTheFewestPaymentsAndOnlyThePayeesConfirmationWritesTheLedger() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 2);
        Traveller host = crew.host();
        Traveller first = crew.companion(0);
        Traveller second = crew.companion(1);
        assertThat(crew.api(host).setUpiId(host.token(), "Host.Pays@OkBank").statusCode()).isEqualTo(200);

        crew.spend(host, 90_000, crew.everyoneIds());

        String hostView = crew.ledgerOf(host);
        assertThat(JsonPath.<Integer>read(hostView, "$.myNetPaise")).isEqualTo(60_000);
        assertThat(JsonPath.<Integer>read(hostView, "$.totalSpentPaise")).isEqualTo(90_000);
        assertThat(JsonPath.<List<Object>>read(hostView, "$.settlements")).as("N - 1 payments for 3 people")
                .hasSize(2);
        assertThat(JsonPath.<List<Object>>read(hostView, "$.settlements[*].payment")).containsOnlyNulls();
        String firstView = crew.ledgerOf(first);
        assertThat(JsonPath.<Integer>read(firstView, "$.myNetPaise")).isEqualTo(-30_000);
        String mine = "$.settlements[?(@.myPart == 'PAYER')]";
        assertThat(JsonPath.<List<String>>read(firstView, mine + ".payment.upiLink")).containsExactly(
                "upi://pay?pa=host.pays@okbank&pn=" + firstNameOf(host) + "&am=300.00&cu=INR"
                        + "&tn=TripMates%20settle-up");
        assertThat(JsonPath.<List<Boolean>>read(firstView, mine + ".canMarkPaid")).containsExactly(true);

        String settlement = crew.settlementId(first, host);
        assertThat(crew.api(first).markPaid(first.token(), settlement).statusCode()).isEqualTo(200);
        assertThat(ledgerRowsFor(settlement)).as("marking as sent writes nothing").isZero();
        HttpResponse<String> confirmed = crew.api(host).confirm(host.token(), settlement);

        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(confirmed.body(), "$.myNetPaise")).isEqualTo(30_000);
        assertThat(ledgerRowsFor(settlement)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                select e.entry_type, e.account_user_id::text from ledger_entries e
                join ledger_transactions t on t.id = e.transaction_id
                where t.settlement_id = ?::uuid order by e.entry_type desc""", settlement))
                .containsExactly(Map.of("entry_type", "DEBIT", "account_user_id", first.userId()),
                        Map.of("entry_type", "CREDIT", "account_user_id", host.userId()));
        assertThat(JsonPath.<Integer>read(crew.ledgerOf(first), "$.myNetPaise")).isZero();
        assertThat(JsonPath.<Integer>read(crew.ledgerOf(second), "$.myNetPaise")).isEqualTo(-30_000);
        assertThat(jdbc.queryForList("select from_status, to_status, reason from settlement_state_transitions "
                + "where settlement_id = ?::uuid order by created_at", settlement)).extracting(row -> row.get("reason"))
                .containsExactly("PLANNED", "MARKED_PAID", "CONFIRMED");
        HttpResponse<String> history = crew.api(first).history(first.token(), crew.tripId(), "");
        assertThat(JsonPath.<List<String>>read(history.body(), "$.items[*].kind")).containsExactly("SETTLEMENT",
                "EXPENSE");
        assertThat(JsonPath.<List<Integer>>read(history.body(), "$.items[*].myEffectPaise"))
                .containsExactly(30_000, -30_000);
        assertLedgerBalances(crew.tripId());
    }

    @Test
    void onlyTravellersOnTheTripCanSeeOrAddAnything() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        String expenseId = crew.spend(crew.host(), 10_000, crew.everyoneIds());
        String settlement = crew.settlementId(crew.companion(0), crew.host());
        Traveller stranger = Traveller.signUp(port, mailpit);
        ChatApi.Pair notLocked = ChatApi.Pair.accepted(port, mailpit);

        for (Traveller outsider : List.of(stranger, notLocked.companion(), notLocked.host())) {
            SettlementApi api = crew.api(outsider);
            String token = outsider.token();
            for (HttpResponse<String> response : List.of(api.ledger(token, crew.tripId()),
                    api.history(token, crew.tripId(), ""),
                    api.addExpense(token, crew.tripId(), equalSplit(UUID.randomUUID(), "Sneaky", 100,
                            List.of(outsider.userId(), crew.host().userId()))),
                    api.reverse(token, expenseId), api.markPaid(token, settlement), api.confirm(token, settlement),
                    api.dispute(token, settlement), api.ledger(token, UUID.randomUUID().toString()),
                    api.confirm(token, UUID.randomUUID().toString()))) {
                assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
                assertThat(response.body()).contains("\"not_found\"");
            }
        }
        // The companion who is accepted but has not met the host yet is not on the ledger of their own trip either.
        HttpResponse<String> early = crew.api(notLocked.companion()).ledger(notLocked.companion().token(),
                notLocked.tripId());
        assertThat(early.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where trip_id = ?::uuid",
                Long.class, crew.tripId())).isEqualTo(1);
    }

    @Test
    void theHostSeesTheLedgerClosedUntilACompanionIsLockedIn() {
        ChatApi.Pair pair = ChatApi.Pair.accepted(port, mailpit);
        SettlementApi api = new SettlementApi(pair.host().http());

        HttpResponse<String> view = api.ledger(pair.host().token(), pair.tripId());
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(view.body(), "$.locked")).isFalse();
        assertThat(JsonPath.<Boolean>read(view.body(), "$.open")).isFalse();
        assertThat(JsonPath.<List<Object>>read(view.body(), "$.members")).hasSize(1);
        HttpResponse<String> refused = api.addExpense(pair.host().token(), pair.tripId(), equalSplit(
                UUID.randomUUID(), "Too early", 1000, List.of(pair.host().userId(), pair.companion().userId())));
        assertProblem(refused, 409, "ledger_not_open");
    }

    @Test
    void confirmingBeforeThePayerMarkedItSentIsRefusedAndWritesNothing() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        crew.spend(crew.host(), 50_000, crew.everyoneIds());
        String settlement = crew.settlementId(crew.companion(0), crew.host());

        assertProblem(crew.api(crew.host()).confirm(crew.host().token(), settlement), 409, "not_marked_paid");
        assertThat(ledgerRowsFor(settlement)).isZero();
        assertThat(status(settlement)).isEqualTo("PROPOSED");
    }

    @Test
    void thePayerCanNeverConfirmTheirOwnPaymentWhateverItsState() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        Traveller payer = crew.companion(0);
        crew.spend(crew.host(), 50_000, crew.everyoneIds());
        String settlement = crew.settlementId(payer, crew.host());

        assertProblem(crew.api(payer).confirm(payer.token(), settlement), 409, "cannot_confirm_own_payment");
        assertThat(crew.api(payer).markPaid(payer.token(), settlement).statusCode()).isEqualTo(200);
        assertProblem(crew.api(payer).confirm(payer.token(), settlement), 409, "cannot_confirm_own_payment");

        assertThat(ledgerRowsFor(settlement)).isZero();
        assertThat(status(settlement)).isEqualTo("MARKED_PAID");
    }

    @Test
    void aTravellerWhoIsNotInAPaymentCannotActOnIt() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 2);
        crew.spend(crew.host(), 90_000, crew.everyoneIds());
        String settlement = crew.settlementId(crew.companion(0), crew.host());
        Traveller bystander = crew.companion(1);
        SettlementApi api = crew.api(bystander);

        assertProblem(api.markPaid(bystander.token(), settlement), 409, "not_the_payer");
        assertProblem(api.confirm(bystander.token(), settlement), 409, "not_the_payee");
        assertProblem(api.dispute(bystander.token(), settlement), 409, "not_a_party");
        assertProblem(crew.api(crew.host()).markPaid(crew.host().token(), settlement), 409, "not_the_payer");
        assertThat(status(settlement)).isEqualTo("PROPOSED");
    }

    @Test
    void aRetriedExpenseIsRecordedOnceAndAReusedIdWithOtherDetailsIsRefused() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        UUID clientId = UUID.randomUUID();
        String body = equalSplit(clientId, "Bus tickets", 12_000, crew.everyoneIds());
        SettlementApi api = crew.api(crew.host());

        assertThat(api.addExpense(crew.host().token(), crew.tripId(), body).statusCode()).isEqualTo(201);
        HttpResponse<String> retry = api.addExpense(crew.host().token(), crew.tripId(), body);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<List<Object>>read(retry.body(), "$.recentExpenses")).hasSize(1);
        assertProblem(api.addExpense(crew.host().token(), crew.tripId(),
                equalSplit(clientId, "Bus tickets", 12_001, crew.everyoneIds())), 409, "expense_id_reused");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where trip_id = ?::uuid",
                Long.class, crew.tripId())).isEqualTo(1);
    }

    @Test
    void anExpenseIsCheckedForEverythingItClaims() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        SettlementApi api = crew.api(crew.host());
        String token = crew.host().token();
        String trip = crew.tripId();
        String hostId = crew.host().userId();
        String companionId = crew.companion(0).userId();
        String strangerId = Traveller.signUp(port, mailpit).userId();

        assertProblem(api.addExpense(token, trip, equalSplit(UUID.randomUUID(), "Snacks", 500,
                List.of(hostId, strangerId))), 400, "participant_not_on_trip");
        assertProblem(api.addExpense(token, trip, exactSplit(UUID.randomUUID(), "Snacks", 500, hostId, 200,
                companionId, 200)), 400, "invalid_split");
        assertProblem(api.addExpense(token, trip, exactSplit(UUID.randomUUID(), "Snacks", 500, hostId, 500,
                companionId, 0)), 400, null);
        assertProblem(api.addExpense(token, trip, equalSplit(UUID.randomUUID(), "Snacks", 500, List.of(hostId))),
                400, "split_needs_someone_else");
        assertProblem(api.addExpense(token, trip, """
                {"clientExpenseId":"%s","description":"Snacks","amountPaise":1250.5,"split":"EQUAL",
                 "participants":[{"userId":"%s"},{"userId":"%s"}]}""".formatted(UUID.randomUUID(), hostId,
                companionId)), 400, "invalid_amount");
        assertProblem(api.addExpense(token, trip, equalSplit(UUID.randomUUID(), "​", 500,
                List.of(hostId, companionId))), 400, "invalid_description");
        assertProblem(api.addExpense(token, trip, equalSplit(UUID.randomUUID(), "Too much", 100_000_001,
                List.of(hostId, companionId))), 400, null);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where trip_id = ?::uuid",
                Long.class, trip)).isZero();

        HttpResponse<String> exact = api.addExpense(token, trip, exactSplit(UUID.randomUUID(), "Snacks", 500,
                hostId, 100, companionId, 400));
        assertThat(exact.statusCode()).as(exact.body()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(exact.body(), "$.myNetPaise")).isEqualTo(400);
    }

    @Test
    void aCorrectionIsAReversingTransactionAndTheExpenseStaysInTheHistory() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        String expense = crew.spend(crew.host(), 20_000, crew.everyoneIds());
        String settlement = crew.settlementId(crew.companion(0), crew.host());

        assertProblem(crew.api(crew.companion(0)).reverse(crew.companion(0).token(), expense), 409,
                "not_your_expense");
        HttpResponse<String> reversed = crew.api(crew.host()).reverse(crew.host().token(), expense);

        assertThat(reversed.statusCode()).as(reversed.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(reversed.body(), "$.myNetPaise")).isZero();
        assertThat(JsonPath.<Integer>read(reversed.body(), "$.totalSpentPaise")).isZero();
        assertThat(JsonPath.<Boolean>read(reversed.body(), "$.recentExpenses[0].reversed")).isTrue();
        assertThat(JsonPath.<Boolean>read(reversed.body(), "$.settledUp")).isTrue();
        assertThat(status(settlement)).as("the old suggestion is voided, not deleted").isEqualTo("VOIDED");
        assertThat(crew.api(crew.host()).reverse(crew.host().token(), expense).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where reverses_id = ?::uuid",
                Long.class, expense)).isEqualTo(1);
        HttpResponse<String> history = crew.api(crew.host()).history(crew.host().token(), crew.tripId(), "");
        assertThat(JsonPath.<List<String>>read(history.body(), "$.items[*].kind"))
                .containsExactly("EXPENSE_REVERSAL", "EXPENSE");
        assertThat(JsonPath.<String>read(history.body(), "$.items[0].reversesId")).isEqualTo(expense);
        assertLedgerBalances(crew.tripId());
    }

    @Test
    void aDisputedPaymentIsHeldOutOfThePlanAndReportedForModeration() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        Traveller payer = crew.companion(0);
        crew.spend(crew.host(), 40_000, crew.everyoneIds());
        String settlement = crew.settlementId(payer, crew.host());
        crew.api(payer).markPaid(payer.token(), settlement);

        HttpResponse<String> disputed = crew.api(crew.host()).dispute(crew.host().token(), settlement);

        assertThat(disputed.statusCode()).as(disputed.body()).isEqualTo(200);
        assertThat(status(settlement)).isEqualTo("DISPUTED");
        assertThat(JsonPath.<List<String>>read(disputed.body(), "$.settlements[*].status")).containsExactly("DISPUTED");
        assertThat(JsonPath.<Boolean>read(disputed.body(), "$.settledUp")).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'SETTLEMENT_DISPUTED' "
                + "and aggregate_id = ?::uuid", Long.class, settlement)).isEqualTo(1);
        assertProblem(crew.api(crew.host()).confirm(crew.host().token(), settlement), 409, "settlement_closed");
        assertThat(ledgerRowsFor(settlement)).isZero();
    }

    @Test
    void aCompanionWhoSteppedBackCanStillSettleButAddsNothingNew() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 2);
        Traveller leaver = crew.companion(1);
        crew.spend(crew.host(), 30_000, crew.everyoneIds());
        jdbc.update("update trip_members set status = 'CANCELLED', cancellation_type = 'SAFETY' "
                + "where trip_id = ?::uuid and user_id = ?::uuid", crew.tripId(), leaver.userId());

        String view = crew.ledgerOf(leaver);
        assertThat(JsonPath.<Boolean>read(view, "$.open")).isFalse();
        assertThat(JsonPath.<List<Boolean>>read(view, "$.members[?(@.me == true)].onTrip")).containsExactly(false);
        assertProblem(crew.api(leaver).addExpense(leaver.token(), crew.tripId(), equalSplit(UUID.randomUUID(),
                "Late", 100, crew.everyoneIds())), 409, "not_on_trip");
        assertProblem(crew.api(crew.host()).addExpense(crew.host().token(), crew.tripId(), equalSplit(
                UUID.randomUUID(), "After they left", 100, crew.everyoneIds())), 400, "participant_not_on_trip");
        String settlement = crew.settlementId(leaver, crew.host());
        assertThat(crew.api(leaver).markPaid(leaver.token(), settlement).statusCode()).isEqualTo(200);
    }

    @Test
    void aUpiIdIsCheckedStoredEncryptedAndOnlyEverShownToSomeoneWhoOwesItsOwner() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        SettlementApi api = crew.api(crew.host());
        String token = crew.host().token();

        assertProblem(api.setUpiId(token, "asha@okbank&am=1"), 400, "invalid_upi_id");
        HttpResponse<String> set = api.setUpiId(token, "asha.rao@okbank");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(api.upiId(token).body(), "$.upiId.upiId")).isEqualTo("asha.rao@okbank");
        byte[] stored = jdbc.queryForObject("select vpa_ciphertext from upi_ids where user_id = ?::uuid",
                byte[].class, crew.host().userId());
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("asha");

        crew.spend(crew.companion(0), 10_000, crew.everyoneIds());
        assertThat(crew.ledgerOf(crew.companion(0))).doesNotContain("asha.rao@okbank");
        assertThat(crew.ledgerOf(crew.host())).as("the host owes, so sees no link to themself")
                .doesNotContain("asha.rao@okbank");

        assertThat(api.removeUpiId(token).statusCode()).isEqualTo(204);
        assertThat(api.upiId(token).body()).contains("\"upiId\":null");
    }

    @Test
    void withNoUpiIdThePayerGetsNoLinkAndIsLeftToAskInChat() {
        Crew crew = Crew.locked(port, mailpit, jdbc, 1);
        crew.spend(crew.host(), 10_000, crew.everyoneIds());

        String view = crew.ledgerOf(crew.companion(0));
        assertThat(JsonPath.<List<String>>read(view, "$.settlements[*].myPart")).containsExactly("PAYER");
        assertThat(JsonPath.<List<Object>>read(view, "$.settlements[*].payment")).containsOnlyNulls();
    }

    private void assertLedgerBalances(String tripId) {
        assertThat(jdbc.queryForList("""
                select transaction_id from ledger_entries where trip_id = ?::uuid group by transaction_id
                having sum(case when entry_type = 'DEBIT' then amount_paise else -amount_paise end) <> 0""",
                tripId)).as("every transaction balances").isEmpty();
        assertThat(jdbc.queryForObject("""
                select coalesce(sum(case when entry_type = 'DEBIT' then amount_paise else -amount_paise end), 0)
                from ledger_entries where trip_id = ?::uuid""", Long.class, tripId)).as("the trip sums to zero")
                .isZero();
    }

    private long ledgerRowsFor(String settlementId) {
        return jdbc.queryForObject("""
                select count(*) from ledger_entries e join ledger_transactions t on t.id = e.transaction_id
                where t.settlement_id = ?::uuid""", Long.class, settlementId);
    }

    private String status(String settlementId) {
        return jdbc.queryForObject("select status from settlements where id = ?::uuid", String.class, settlementId);
    }

    private String firstNameOf(Traveller traveller) {
        return jdbc.queryForObject("select first_name from user_public_facts where user_id = ?::uuid", String.class,
                traveller.userId());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (code != null) {
            assertThat(response.body()).contains("\"" + code + "\"");
        }
    }
}
