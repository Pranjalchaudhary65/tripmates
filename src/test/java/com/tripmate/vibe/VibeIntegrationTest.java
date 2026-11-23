package com.tripmate.vibe;

import com.jayway.jsonpath.JsonPath;
import com.tripmate.identity.RegistrationApi;
import com.tripmate.identity.SessionApi;
import com.tripmate.support.IntegrationTest;
import com.tripmate.support.LocalHttp;
import com.tripmate.support.MailpitClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.tripmate.identity.RegistrationApi.STRONG_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class VibeIntegrationTest {

    private static final String ADJUSTED = """
            {"budgetBand":"COMFORT","pace":"SLOW","wakeTime":"NIGHT_OWL","planningStyle":"ROUGH_PLAN",
             "socialEnergy":2,"riskAppetite":4,"foodAdventurousness":5,"expenseSplitStyle":"WHOEVER_CAN"}""";

    @LocalServerPort
    private int port;

    @Autowired
    private MailpitClient mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private VibeApi vibe;

    private SessionApi.Session session;

    @BeforeEach
    void setUp() {
        LocalHttp http = new LocalHttp(port);
        String email = RegistrationApi.uniqueEmail();
        new RegistrationApi(http, mailpit).registerVerified(email);
        session = new SessionApi(http).signIn(email, STRONG_PASSWORD);
        vibe = new VibeApi(http);
    }

    @Test
    void theQuizListsQuestionsAndLabelsButNeverHowAnswersScore() {
        HttpResponse<String> quiz = vibe.quiz(token());

        assertThat(quiz.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(quiz.body(), "$.questions")).hasSize(14);
        assertThat(JsonPath.<String>read(quiz.body(), "$.questions[0].options[0].label"))
                .isEqualTo("Dorm bed in a hostel");
        assertThat(quiz.body()).doesNotContain("\"value\"", "dimension", "BUDGET_BAND");
        assertThat((Object) JsonPath.read(quiz.body(), "$.draft")).isNull();
        assertThat(JsonPath.<Boolean>read(quiz.body(), "$.hasProfile")).isFalse();
    }

    @Test
    void savedAnswersComeBackWithTheQuizSoItCanBeResumed() {
        HttpResponse<String> saved = vibe.saveDraft(token(), Map.of("budget-stay", "c", "social-evenings", "4"));

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        HttpResponse<String> quiz = vibe.quiz(token());
        assertThat(JsonPath.<Map<String, String>>read(quiz.body(), "$.draft.answers"))
                .isEqualTo(Map.of("budget-stay", "c", "social-evenings", "4"));
    }

    @Test
    void aDraftReplacesTheOneBeforeIt() {
        vibe.saveDraft(token(), Map.of("budget-stay", "c", "pace-day", "a"));
        vibe.saveDraft(token(), Map.of("budget-stay", "d"));

        assertThat(JsonPath.<Map<String, String>>read(vibe.quiz(token()).body(), "$.draft.answers"))
                .isEqualTo(Map.of("budget-stay", "d"));
    }

    @Test
    void anAnswerThatIsNotAnOptionOfItsQuestionIsRefused() {
        for (Map<String, String> bad : List.of(Map.of("budget-stay", "z"), Map.of("not-a-question", "a"),
                Map.of("budget-stay", "1"), Map.of("social-evenings", "<script>"))) {
            HttpResponse<String> response = vibe.saveDraft(token(), bad);
            assertThat(response.statusCode()).as("%s", bad).isEqualTo(400);
            assertThat(code(response)).isEqualTo("invalid_quiz_answer");
        }
        assertThat((Object) JsonPath.read(vibe.quiz(token()).body(), "$.draft")).isNull();
    }

    @Test
    void anIncompleteQuizCannotBeSubmitted() {
        Map<String, String> partial = new HashMap<>(VibeApi.FIRST_OPTIONS);
        partial.remove("expense-split");

        HttpResponse<String> response = vibe.submit(token(), partial);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("quiz_incomplete");
        assertThat(JsonPath.<Object>read(vibe.profile(token()).body(), "$.profile")).isNull();
    }

    @Test
    void submittingScoresTheProfileDropsTheDraftAndTellsTheMatcher() {
        vibe.saveDraft(token(), Map.of("budget-stay", "c"));

        HttpResponse<String> submitted = vibe.submit(token(), VibeApi.answers(Map.of(
                "budget-stay", "d", "budget-transport", "c", "expense-split", "whoever")));

        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(submitted.body(), "$.profile.budgetBand")).isEqualTo("LUXURY");
        assertThat(JsonPath.<String>read(submitted.body(), "$.profile.pace")).isEqualTo("PACKED");
        assertThat(JsonPath.<Integer>read(submitted.body(), "$.profile.socialEnergy")).isEqualTo(3);
        assertThat(JsonPath.<String>read(submitted.body(), "$.profile.expenseSplitStyle")).isEqualTo("WHOEVER_CAN");
        assertThat((Object) JsonPath.read(submitted.body(), "$.profile.adjustedAt")).isNull();
        assertThat(submitted.body()).doesNotContain("rawAnswers", "budget-stay");
        assertThat((Object) JsonPath.read(vibe.quiz(token()).body(), "$.draft")).isNull();
        assertThat(JsonPath.<Boolean>read(vibe.quiz(token()).body(), "$.hasProfile")).isTrue();
        assertThat(jdbc.queryForObject(
                "select raw_answers ->> 'budget-stay' from vibe_profiles where user_id = ?::uuid",
                String.class, session.userId())).isEqualTo("d");
        assertThat(outboxEvents("VIBE_PROFILE_CHANGED")).isEqualTo(1);
    }

    @Test
    void retakingTheQuizReplacesTheProfileAndAnyHandAdjustment() {
        vibe.submit(token(), VibeApi.FIRST_OPTIONS);
        vibe.adjust(token(), ADJUSTED);

        HttpResponse<String> retaken = vibe.submit(token(), VibeApi.FIRST_OPTIONS);

        assertThat(JsonPath.<String>read(retaken.body(), "$.profile.budgetBand")).isEqualTo("SHOESTRING");
        assertThat((Object) JsonPath.read(retaken.body(), "$.profile.adjustedAt")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from vibe_profiles where user_id = ?::uuid", Integer.class,
                session.userId())).isEqualTo(1);
    }

    @Test
    void adjustingNeedsAProfileThenRecordsThatItWasAdjusted() {
        HttpResponse<String> early = vibe.adjust(token(), ADJUSTED);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(code(early)).isEqualTo("vibe_profile_missing");

        vibe.submit(token(), VibeApi.FIRST_OPTIONS);
        HttpResponse<String> adjusted = vibe.adjust(token(), ADJUSTED);

        assertThat(adjusted.statusCode()).as(adjusted.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(adjusted.body(), "$.profile.budgetBand")).isEqualTo("COMFORT");
        assertThat(JsonPath.<Integer>read(adjusted.body(), "$.profile.foodAdventurousness")).isEqualTo(5);
        assertThat((Object) JsonPath.read(adjusted.body(), "$.profile.adjustedAt")).isNotNull();
        assertThat(jdbc.queryForObject(
                "select raw_answers ->> 'budget-stay' from vibe_profiles where user_id = ?::uuid",
                String.class, session.userId())).as("the answers stay as given").isEqualTo("a");
        assertThat(outboxEvents("VIBE_PROFILE_CHANGED")).isEqualTo(2);
    }

    @Test
    void anAdjustmentOutsideTheScalesIsRefusedAgainstItsField() {
        vibe.submit(token(), VibeApi.FIRST_OPTIONS);

        HttpResponse<String> response = vibe.adjust(token(),
                ADJUSTED.replace("\"socialEnergy\":2", "\"socialEnergy\":6"));
        HttpResponse<String> unknown = vibe.adjust(token(), ADJUSTED.replace("COMFORT", "BILLIONAIRE"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.errors[0].field")).isEqualTo("socialEnergy");
        assertThat(unknown.statusCode()).isEqualTo(400);
    }

    @Test
    void dealBreakersCanBeSetWithoutAProfileAndAreChecked() {
        HttpResponse<String> set = vibe.dealBreakers(token(), """
                {"smoking":"NO","drinking":"EITHER","genderPreference":"SAME","minTrustScore":40}""");

        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat((Object) JsonPath.read(set.body(), "$.profile")).isNull();
        assertThat(JsonPath.<String>read(set.body(), "$.dealBreakers.genderPreference")).isEqualTo("SAME");
        assertThat(JsonPath.<Integer>read(vibe.profile(token()).body(), "$.dealBreakers.minTrustScore")).isEqualTo(40);
        assertThat(outboxEvents("DEAL_BREAKERS_CHANGED")).isEqualTo(1);

        HttpResponse<String> tooHigh = vibe.dealBreakers(token(), """
                {"smoking":"NO","drinking":"EITHER","genderPreference":"SAME","minTrustScore":101}""");
        HttpResponse<String> missing = vibe.dealBreakers(token(), """
                {"smoking":"NO","genderPreference":"SAME","minTrustScore":10}""");
        assertThat(tooHigh.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(tooHigh.body(), "$.errors[0].field")).isEqualTo("minTrustScore");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<Integer>read(vibe.profile(token()).body(), "$.dealBreakers.minTrustScore")).isEqualTo(40);
    }

    @Test
    void theSameQuizSubmittedTwiceAtOnceLandsAsOneProfile() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> submit = () -> {
            start.await();
            return vibe.submit(token(), VibeApi.FIRST_OPTIONS).statusCode();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(submit);
            Future<Integer> second = pool.submit(submit);
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsOnly(200);
        }
        assertThat(jdbc.queryForObject("select count(*) from vibe_profiles where user_id = ?::uuid", Integer.class,
                session.userId())).isEqualTo(1);
    }

    @Test
    void anotherAccountSeesNothingOfThisOne() {
        vibe.submit(token(), VibeApi.answers(Map.of("budget-stay", "d", "budget-transport", "d")));
        LocalHttp otherHttp = new LocalHttp(port);
        String otherEmail = RegistrationApi.uniqueEmail();
        new RegistrationApi(otherHttp, mailpit).registerVerified(otherEmail);
        SessionApi.Session other = new SessionApi(otherHttp).signIn(otherEmail, STRONG_PASSWORD);

        HttpResponse<String> theirs = new VibeApi(otherHttp).profile(other.accessToken());

        assertThat(theirs.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(theirs.body(), "$.profile")).isNull();
    }

    private int outboxEvents(String type) {
        Integer count = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = ?",
                Integer.class, session.userId(), type);
        return count == null ? 0 : count;
    }

    private String token() {
        return session.accessToken();
    }

    private static String code(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.code");
    }
}
