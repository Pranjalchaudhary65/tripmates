package com.tripmate.support;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;

/**
 * Reads what the application actually sent over SMTP, through Mailpit's HTTP
 * API. Mail is sent asynchronously after commit, so the {@code await} methods
 * poll until the expected message lands.
 */
public final class MailpitClient {

    public static final String VERIFICATION_SUBJECT = "Your TripMates verification code";

    public static final String EXISTING_ACCOUNT_SUBJECT = "Someone tried to sign up with your email";

    private static final Pattern CODE = Pattern.compile("verification code is: (\\d{6})");

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newHttpClient();

    private final String baseUrl;

    public MailpitClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Subjects of every message sent to {@code address}, newest first. */
    public List<String> subjectsTo(String address) {
        return search(address).read("$.messages[*].Subject");
    }

    /** Waits until {@code address} has received {@code count} verification emails and returns the newest code. */
    public String awaitVerificationCode(String address, int count) {
        await().atMost(TIMEOUT).until(() -> countWithSubject(address, VERIFICATION_SUBJECT) >= count);
        DocumentContext results = search(address);
        List<String> ids = results.read("$.messages[?(@.Subject == '" + VERIFICATION_SUBJECT + "')].ID");
        String text = JsonPath.parse(get("/api/v1/message/" + ids.getFirst())).read("$.Text");
        Matcher matcher = CODE.matcher(text);
        if (!matcher.find()) {
            throw new AssertionError("No code in verification email: " + text);
        }
        return matcher.group(1);
    }

    public String awaitVerificationCode(String address) {
        return awaitVerificationCode(address, 1);
    }

    public void awaitSubject(String address, String subject) {
        await().atMost(TIMEOUT).until(() -> countWithSubject(address, subject) >= 1);
    }

    public long countWithSubject(String address, String subject) {
        return subjectsTo(address).stream().filter(subject::equals).count();
    }

    private DocumentContext search(String address) {
        String query = URLEncoder.encode("to:\"" + address + "\"", StandardCharsets.UTF_8);
        return JsonPath.parse(get("/api/v1/search?query=" + query));
    }

    private String get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit answered " + response.statusCode() + " for " + path);
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit API call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Mailpit", e);
        }
    }
}
