package com.tripmate.vibe;

import com.tripmate.support.LocalHttp;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Calls to the vibe routes, and quiz answers to send them. */
public final class VibeApi {

    public static final String QUIZ = "/api/v1/vibe/quiz";

    public static final String DRAFT = "/api/v1/vibe/quiz/draft";

    public static final String SUBMIT = "/api/v1/vibe/quiz/submit";

    public static final String PROFILE = "/api/v1/vibe/profile";

    public static final String DEAL_BREAKERS = "/api/v1/vibe/deal-breakers";

    /** Every question, each with its first option: the cheapest, busiest, earliest, most planned traveller. */
    public static final Map<String, String> FIRST_OPTIONS = Map.ofEntries(
            Map.entry("budget-stay", "a"), Map.entry("budget-transport", "a"),
            Map.entry("pace-day", "a"), Map.entry("pace-rest", "a"),
            Map.entry("wake-morning", "a"), Map.entry("wake-sunrise", "a"),
            Map.entry("plan-before", "a"), Map.entry("plan-change", "a"),
            Map.entry("social-evenings", "1"), Map.entry("social-alone", "1"),
            Map.entry("risk-adventure", "1"), Map.entry("risk-detour", "1"),
            Map.entry("food-street", "1"), Map.entry("expense-split", "strict"));

    private final LocalHttp http;

    public VibeApi(LocalHttp http) {
        this.http = http;
    }

    /** {@link #FIRST_OPTIONS} with some answers replaced. */
    public static Map<String, String> answers(Map<String, String> changes) {
        Map<String, String> answers = new TreeMap<>(FIRST_OPTIONS);
        answers.putAll(changes);
        return answers;
    }

    public static String json(Map<String, String> answers) {
        return answers.entrySet().stream()
                .map(entry -> "\"%s\":\"%s\"".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(",", "{\"answers\":{", "}}"));
    }

    public HttpResponse<String> quiz(String accessToken) {
        return http.get(QUIZ, bearer(accessToken));
    }

    public HttpResponse<String> saveDraft(String accessToken, Map<String, String> answers) {
        return put(DRAFT, json(answers), accessToken);
    }

    public HttpResponse<String> submit(String accessToken, Map<String, String> answers) {
        return http.postJson(SUBMIT, json(answers), bearer(accessToken));
    }

    public HttpResponse<String> profile(String accessToken) {
        return http.get(PROFILE, bearer(accessToken));
    }

    public HttpResponse<String> adjust(String accessToken, String json) {
        return put(PROFILE, json, accessToken);
    }

    public HttpResponse<String> dealBreakers(String accessToken, String json) {
        return put(DEAL_BREAKERS, json, accessToken);
    }

    private HttpResponse<String> put(String path, String json, String accessToken) {
        return http.putJson(path, json, bearer(accessToken));
    }

    private static String[] bearer(String accessToken) {
        return new String[] {"Authorization", "Bearer " + accessToken};
    }
}
