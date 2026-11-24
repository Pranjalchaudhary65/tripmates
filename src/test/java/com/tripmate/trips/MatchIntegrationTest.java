package com.tripmate.trips;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.MailpitClient;
import com.tripmate.trips.TripApi.Place;
import com.tripmate.vibe.VibeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching end to end: deal-breakers filter in SQL before any score, the
 * score comes with its reasons, and the cache is only ever served for the
 * profiles as they are now.
 */
@IntegrationTest
class MatchIntegrationTest {

    /** Cheapest, busiest, earliest, most planned: the quiz's first option everywhere (VibeApi.FIRST_OPTIONS). */
    private static final Map<String, String> PLANNER = VibeApi.FIRST_OPTIONS;

    /** The other end on every question but food and expenses. */
    private static final Map<String, String> DRIFTER = VibeApi.answers(Map.ofEntries(
            Map.entry("budget-stay", "d"), Map.entry("budget-transport", "d"),
            Map.entry("pace-day", "c"), Map.entry("pace-rest", "c"),
            Map.entry("wake-morning", "c"), Map.entry("wake-sunrise", "c"),
            Map.entry("plan-before", "c"), Map.entry("plan-change", "c"),
            Map.entry("social-evenings", "5"), Map.entry("social-alone", "5"),
            Map.entry("risk-adventure", "5"), Map.entry("risk-detour", "5")));

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private Traveller host;

    private Traveller viewer;

    private Place place;

    @BeforeEach
    void setUp() {
        host = Traveller.signUp(port, mailpit);
        viewer = Traveller.signUp(port, mailpit);
        place = TripApi.uniquePlace();
    }

    @Test
    void alikeTravellersScoreHighWithTheReasonsAndNoneOfTheHostsProfile() {
        takeQuiz(host, PLANNER);
        takeQuiz(viewer, PLANNER);
        String tripId = openTrip("SHOESTRING");

        HttpResponse<String> detail = viewer.trips().detail(viewer.token(), tripId);

        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(detail.body(), "$.match.status")).isEqualTo("SCORED");
        assertThat(JsonPath.<Integer>read(detail.body(), "$.match.score")).isEqualTo(100);
        assertThat(JsonPath.<Integer>read(detail.body(), "$.trip.matchScore")).isEqualTo(100);
        assertThat(JsonPath.<List<String>>read(detail.body(), "$.match.matches[*].label"))
                .contains("You are both early birds.");
        assertThat(JsonPath.<List<String>>read(detail.body(), "$.match.mismatches")).isEmpty();
        assertThat(detail.body()).as("reasons in words, never the host's profile values")
                .doesNotContain(host.userId(), "EARLY_BIRD", "DETAILED_ITINERARY", "socialEnergy", "rawAnswers");
    }

    @Test
    void differentTravellersScoreLowAndHearWhatDiffers() {
        takeQuiz(host, DRIFTER);
        takeQuiz(viewer, PLANNER);
        String tripId = openTrip("LUXURY");

        HttpResponse<String> detail = viewer.trips().detail(viewer.token(), tripId);

        assertThat(JsonPath.<Integer>read(detail.body(), "$.match.score")).isLessThan(40);
        assertThat(JsonPath.<List<String>>read(detail.body(), "$.match.mismatches[*].dimension"))
                .contains("BUDGET", "WAKE_TIME", "PACE");
        assertThat(JsonPath.<List<String>>read(detail.body(), "$.match.mismatches[*].label"))
                .contains("You are an early bird; the host is a night owl.");
    }

    @Test
    void withoutAVibeOnEitherSideThereIsNothingToScore() {
        String tripId = openTrip("MID");

        HttpResponse<String> noProfile = viewer.trips().detail(viewer.token(), tripId);
        assertThat(JsonPath.<String>read(noProfile.body(), "$.match.status")).isEqualTo("NEEDS_YOUR_PROFILE");
        assertThat((Object) JsonPath.read(noProfile.body(), "$.match.score")).isNull();

        takeQuiz(viewer, PLANNER);
        HttpResponse<String> hostWithout = viewer.trips().detail(viewer.token(), tripId);
        assertThat(JsonPath.<String>read(hostWithout.body(), "$.match.status")).isEqualTo("HOST_HAS_NO_PROFILE");
        assertThat((Object) JsonPath.read(hostWithout.body(), "$.trip.matchScore")).isNull();
    }

    @Test
    void theHostGetsNoMatchOnTheirOwnTrip() {
        takeQuiz(host, PLANNER);
        String tripId = openTrip("MID");

        HttpResponse<String> own = host.trips().detail(host.token(), tripId);

        assertThat((Object) JsonPath.read(own.body(), "$.match")).isNull();
        assertThat((Object) JsonPath.read(own.body(), "$.trip.matchScore")).isNull();
    }

    @Test
    void browseCarriesTheMatchScore() {
        takeQuiz(host, PLANNER);
        takeQuiz(viewer, PLANNER);
        openTrip("SHOESTRING");

        HttpResponse<String> found = viewer.trips().browseNear(viewer.token(), place, "");

        assertThat(JsonPath.<List<Integer>>read(found.body(), "$.items[*].matchScore")).containsExactly(100);
    }

    @Test
    void aSmokingClashHidesTheTripBlocksTheMatchAndRefusesTheRequestFromEitherSide() {
        setDealBreakers(host, "NO", "EITHER", "ANY", 0);
        setDealBreakers(viewer, "OK", "EITHER", "ANY", 0);
        String tripId = openTrip("MID");

        assertBlocked(tripId, "SMOKING");

        setDealBreakers(host, "OK", "EITHER", "ANY", 0);
        setDealBreakers(viewer, "NO", "EITHER", "ANY", 0);
        assertBlocked(tripId, "SMOKING");
    }

    @Test
    void noPreferenceGoesWithEitherHabit() {
        setDealBreakers(host, "NO", "OK", "ANY", 0);
        setDealBreakers(viewer, "EITHER", "EITHER", "ANY", 0);
        String tripId = openTrip("MID");

        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, ""))).containsExactly(tripId);
        assertThat(viewer.trips().requestToJoin(viewer.token(), tripId).statusCode()).isEqualTo(201);
    }

    @Test
    void aDrinkingClashIsADealBreakerToo() {
        setDealBreakers(host, "EITHER", "OK", "ANY", 0);
        setDealBreakers(viewer, "EITHER", "NO", "ANY", 0);

        assertBlocked(openTrip("MID"), "DRINKING");
    }

    @Test
    void aSameGenderTripIsOnlyForPeopleOfTheHostsGender() {
        setDealBreakers(host, "EITHER", "EITHER", "SAME", 0);
        String tripId = openTrip("MID");

        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, "")))
                .as("both FEMALE: the trip is listed").containsExactly(tripId);

        setGender(viewer, "MALE");
        assertBlocked(tripId, "HOST_GENDER_PREFERENCE");
    }

    @Test
    void aSameGenderPreferenceCannotBeMetWithoutAGenderOnRecord() {
        setDealBreakers(viewer, "EITHER", "EITHER", "SAME", 0);
        setGender(host, "PREFER_NOT_TO_SAY");
        setGender(viewer, "PREFER_NOT_TO_SAY");

        assertBlocked(openTrip("MID"), "MY_GENDER_PREFERENCE");
    }

    @Test
    void trustMinimumsHoldInBothDirections() {
        setDealBreakers(viewer, "EITHER", "EITHER", "ANY", 40);
        String tripId = openTrip("MID");
        assertBlocked(tripId, "MY_TRUST_MINIMUM");

        jdbc.update("update users set trust_score = 55 where id = ?::uuid", host.userId());
        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, ""))).containsExactly(tripId);

        setDealBreakers(host, "EITHER", "EITHER", "ANY", 60);
        assertBlocked(tripId, "HOST_TRUST_MINIMUM");
    }

    @Test
    void aCachedScoreIsServedWhileBothProfilesAreUnchanged() {
        takeQuiz(host, PLANNER);
        takeQuiz(viewer, PLANNER);
        String tripId = openTrip("SHOESTRING");
        viewer.trips().detail(viewer.token(), tripId);
        assertThat(cachedScore(tripId)).isEqualTo(100);

        jdbc.update("update match_score_cache set score = 7 where trip_id = ?::uuid and user_id = ?::uuid",
                tripId, viewer.userId());

        assertThat(JsonPath.<Integer>read(viewer.trips().detail(viewer.token(), tripId).body(), "$.match.score"))
                .as("the cached row is what answers").isEqualTo(7);
    }

    @Test
    void aProfileChangeOnEitherSideMakesTheCachedScoreStale() {
        takeQuiz(host, PLANNER);
        takeQuiz(viewer, PLANNER);
        String tripId = openTrip("SHOESTRING");
        viewer.trips().detail(viewer.token(), tripId);
        jdbc.update("update match_score_cache set score = 7 where trip_id = ?::uuid and user_id = ?::uuid",
                tripId, viewer.userId());

        takeQuiz(host, DRIFTER);

        int afterHostChange = JsonPath.read(viewer.trips().detail(viewer.token(), tripId).body(), "$.match.score");
        assertThat(afterHostChange).isNotEqualTo(7).isLessThan(100);
        assertThat(cachedScore(tripId)).isEqualTo(afterHostChange);

        jdbc.update("update match_score_cache set score = 7 where trip_id = ?::uuid and user_id = ?::uuid",
                tripId, viewer.userId());
        takeQuiz(viewer, DRIFTER);

        assertThat(JsonPath.<Integer>read(viewer.trips().detail(viewer.token(), tripId).body(), "$.match.score"))
                .as("the viewer's own change counts too").isNotEqualTo(7);
    }

    @Test
    void aCachedRowThatDoesNotReadBackIsRecomputed() {
        takeQuiz(host, PLANNER);
        takeQuiz(viewer, PLANNER);
        String tripId = openTrip("SHOESTRING");
        viewer.trips().detail(viewer.token(), tripId);
        jdbc.update("""
                update match_score_cache set score = 7, explanation = '{"matches":[null],"mismatches":[]}'
                where trip_id = ?::uuid and user_id = ?::uuid""", tripId, viewer.userId());

        HttpResponse<String> detail = viewer.trips().detail(viewer.token(), tripId);

        assertThat(JsonPath.<Integer>read(detail.body(), "$.match.score")).isEqualTo(100);
    }

    private void assertBlocked(String tripId, String rule) {
        assertThat(ids(viewer.trips().browseNear(viewer.token(), place, ""))).as("hidden from browse").isEmpty();

        HttpResponse<String> detail = viewer.trips().detail(viewer.token(), tripId);
        assertThat(detail.statusCode()).as("still readable through a link").isEqualTo(200);
        assertThat(JsonPath.<String>read(detail.body(), "$.match.status")).isEqualTo("BLOCKED");
        assertThat(JsonPath.<List<String>>read(detail.body(), "$.match.blockers[*].rule")).containsExactly(rule);
        assertThat((Object) JsonPath.read(detail.body(), "$.match.score")).isNull();

        HttpResponse<String> join = viewer.trips().requestToJoin(viewer.token(), tripId);
        assertThat(join.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(join.body(), "$.code")).isEqualTo("deal_breaker_conflict");
        assertThat(jdbc.queryForObject("select count(*) from trip_members where trip_id = ?::uuid", Integer.class,
                tripId)).isZero();
    }

    private String openTrip(String budget) {
        return host.trips().open(host.token(),
                TripApi.tripJson(place, 3, TripApi.today().plusDays(7), TripApi.today().plusDays(9), budget));
    }

    private static void takeQuiz(Traveller traveller, Map<String, String> answers) {
        HttpResponse<String> taken = new VibeApi(traveller.http()).submit(traveller.token(), answers);
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(200);
    }

    private static void setDealBreakers(Traveller traveller, String smoking, String drinking, String gender,
                                        int minTrust) {
        HttpResponse<String> set = new VibeApi(traveller.http()).dealBreakers(traveller.token(), """
                {"smoking":"%s","drinking":"%s","genderPreference":"%s","minTrustScore":%d}"""
                .formatted(smoking, drinking, gender, minTrust));
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
    }

    private void setGender(Traveller traveller, String gender) {
        jdbc.update("update users set gender = ? where id = ?::uuid", gender, traveller.userId());
    }

    private Integer cachedScore(String tripId) {
        return jdbc.queryForObject("select score from match_score_cache where trip_id = ?::uuid and user_id = ?::uuid",
                Integer.class, tripId, viewer.userId());
    }

    private static List<String> ids(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.items[*].id");
    }
}
