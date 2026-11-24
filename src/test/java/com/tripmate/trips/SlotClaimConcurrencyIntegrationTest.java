package com.tripmate.trips;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slot claims raced for real: every accept is released at once from its own
 * thread, against one trip. No lock on the host's account serialises them;
 * only the trip's optimistic lock does.
 */
@IntegrationTest
class SlotClaimConcurrencyIntegrationTest {

    private static final int CLAIMS = 8;

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void manyParallelClaimsOnTheLastSlotLetExactlyOneIn() throws Exception {
        assertExactlyThisManyWin(1);
    }

    @Test
    void parallelClaimsOnSeveralSlotsFillEveryOneAndNoMore() throws Exception {
        assertExactlyThisManyWin(3);
    }

    @Test
    void theLastSlotOfAPartlyFilledTripGoesToExactlyOneOfManyParallelClaims() throws Exception {
        Traveller host = Traveller.signUp(port, mailpit);
        String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(), 2));
        Traveller first = Traveller.signUp(port, mailpit);
        HttpResponse<String> taken = host.trips().accept(host.token(), first.trips().join(first.token(), tripId));
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(200);

        assertRaceFills(host, tripId, 2, 1);
    }

    private void assertExactlyThisManyWin(int slots) throws Exception {
        Traveller host = Traveller.signUp(port, mailpit);
        String tripId = host.trips().open(host.token(), TripApi.tripJson(TripApi.uniquePlace(), slots));
        assertRaceFills(host, tripId, slots, slots);
    }

    /** Races CLAIMS accepts at a trip with slotsLeft of totalSlots free: exactly slotsLeft win and it ends full. */
    private void assertRaceFills(Traveller host, String tripId, int totalSlots, int slotsLeft) throws Exception {
        List<String> requests = new ArrayList<>();
        for (int i = 0; i < CLAIMS; i++) {
            Traveller requester = Traveller.signUp(port, mailpit);
            requests.add(requester.trips().join(requester.token(), tripId));
        }

        List<HttpResponse<String>> responses = raceAccepts(host, requests);

        long won = responses.stream().filter(r -> r.statusCode() == 200).count();
        List<String> lostCodes = responses.stream().filter(r -> r.statusCode() != 200)
                .map(r -> JsonPath.<String>read(r.body(), "$.code")).toList();
        assertThat(won).isEqualTo(slotsLeft);
        assertThat(lostCodes).hasSize(CLAIMS - slotsLeft).containsOnly("trip_full");
        assertThat(jdbc.queryForObject("select filled_slots from trips where id = ?::uuid", Integer.class, tripId))
                .isEqualTo(totalSlots);
        assertThat(jdbc.queryForObject("select status from trips where id = ?::uuid", String.class, tripId))
                .isEqualTo("FULL");
        assertThat(jdbc.queryForObject("select count(*) from trip_members where trip_id = ?::uuid "
                + "and status = 'ACCEPTED'", Integer.class, tripId)).isEqualTo(totalSlots);
    }

    private static List<HttpResponse<String>> raceAccepts(Traveller host, List<String> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String requestId : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return host.trips().accept(host.token(), requestId);
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
