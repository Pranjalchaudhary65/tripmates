package com.tripmate.settlement;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.Traveller;
import com.tripmate.trips.TripApi;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls to the settle-up and UPI ID routes, and a locked trip to call them on. */
public final class SettlementApi {

    public static final String UPI_ID = "/api/v1/users/me/upi-id";

    private final LocalHttp http;

    public SettlementApi(LocalHttp http) {
        this.http = http;
    }

    public HttpResponse<String> ledger(String token, String tripId) {
        return http.get("/api/v1/trips/" + tripId + "/ledger", bearer(token));
    }

    public HttpResponse<String> history(String token, String tripId, String query) {
        return http.get("/api/v1/trips/" + tripId + "/ledger/history" + (query.isEmpty() ? "" : "?" + query),
                bearer(token));
    }

    public HttpResponse<String> addExpense(String token, String tripId, String json) {
        return http.postJson("/api/v1/trips/" + tripId + "/expenses", json, bearer(token));
    }

    public HttpResponse<String> reverse(String token, String expenseId) {
        return http.post("/api/v1/expenses/" + expenseId + "/reverse", bearer(token));
    }

    public HttpResponse<String> markPaid(String token, String settlementId) {
        return http.post("/api/v1/settlements/" + settlementId + "/mark-paid", bearer(token));
    }

    public HttpResponse<String> confirm(String token, String settlementId) {
        return http.post("/api/v1/settlements/" + settlementId + "/confirm", bearer(token));
    }

    public HttpResponse<String> dispute(String token, String settlementId) {
        return http.post("/api/v1/settlements/" + settlementId + "/dispute", bearer(token));
    }

    public HttpResponse<String> setUpiId(String token, String upiId) {
        return http.putJson(UPI_ID, "{\"upiId\":\"" + upiId + "\"}", bearer(token));
    }

    public HttpResponse<String> upiId(String token) {
        return http.get(UPI_ID, bearer(token));
    }

    public HttpResponse<String> removeUpiId(String token) {
        return http.delete(UPI_ID, bearer(token));
    }

    /** An equal split among the given people. */
    public static String equalSplit(UUID clientExpenseId, String description, long amountPaise, List<String> userIds) {
        return """
                {"clientExpenseId":"%s","description":"%s","amountPaise":%d,"split":"EQUAL","participants":[%s]}"""
                .formatted(clientExpenseId, description, amountPaise, userIds.stream()
                        .map(id -> "{\"userId\":\"" + id + "\"}").collect(Collectors.joining(",")));
    }

    /** An exact split: alternating user ID and share in paise. */
    public static String exactSplit(UUID clientExpenseId, String description, long amountPaise, Object... shares) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < shares.length; i += 2) {
            parts.add("{\"userId\":\"%s\",\"sharePaise\":%s}".formatted(shares[i], shares[i + 1]));
        }
        return """
                {"clientExpenseId":"%s","description":"%s","amountPaise":%d,"split":"EXACT","participants":[%s]}"""
                .formatted(clientExpenseId, description, amountPaise, String.join(",", parts));
    }

    private static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    /**
     * A host and companions on one trip, every companion accepted and then
     * locked in. The lock is written directly: the meet-up's own tests cover
     * how a companion earns it.
     */
    public record Crew(Traveller host, List<Traveller> companions, String tripId, JdbcTemplate jdbc) {

        public static Crew locked(int port, MailpitClient mailpit, JdbcTemplate jdbc, int companionCount) {
            Traveller host = Traveller.signUp(port, mailpit);
            String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(),
                    Math.max(2, companionCount + 1)));
            List<Traveller> companions = new ArrayList<>();
            for (int i = 0; i < companionCount; i++) {
                Traveller companion = Traveller.signUp(port, mailpit);
                String requestId = companion.trips().join(companion.token(), tripId);
                HttpResponse<String> accepted = host.trips().accept(host.token(), requestId);
                assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
                jdbc.update("update trip_members set status = 'LOCKED', video_verified_at = now(), "
                        + "locked_at = now() where id = ?::uuid", requestId);
                companions.add(companion);
            }
            return new Crew(host, List.copyOf(companions), tripId, jdbc);
        }

        public Traveller companion(int index) {
            return companions.get(index);
        }

        public List<Traveller> everyone() {
            List<Traveller> all = new ArrayList<>();
            all.add(host);
            all.addAll(companions);
            return all;
        }

        public List<String> everyoneIds() {
            return everyone().stream().map(Traveller::userId).toList();
        }

        public SettlementApi api(Traveller traveller) {
            return new SettlementApi(traveller.http());
        }

        /** Records an equal split paid by {@code payer} and fails the test unless it is accepted. */
        public String spend(Traveller payer, long amountPaise, List<String> sharers) {
            HttpResponse<String> response = api(payer).addExpense(payer.token(), tripId,
                    equalSplit(UUID.randomUUID(), "Shared costs", amountPaise, sharers));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            return JsonPath.read(response.body(), "$.recentExpenses[0].id");
        }

        public String ledgerOf(Traveller traveller) {
            HttpResponse<String> response = api(traveller).ledger(traveller.token(), tripId);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return response.body();
        }

        /** The one open settlement from {@code payer} to {@code payee}. */
        public String settlementId(Traveller payer, Traveller payee) {
            List<String> ids = JsonPath.read(ledgerOf(payer), "$.settlements[?(@.from.userId == '"
                    + payer.userId() + "' && @.to.userId == '" + payee.userId()
                    + "' && (@.status == 'PROPOSED' || @.status == 'MARKED_PAID'))].id");
            assertThat(ids).hasSize(1);
            return ids.getFirst();
        }
    }
}
