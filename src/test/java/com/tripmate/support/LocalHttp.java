package com.tripmate.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real HTTP calls against the running test server, through Tomcat and the
 * full servlet filter chain. MockMvc skips the container, so it cannot see
 * the forward to /error or headers set by filters outside Spring MVC.
 *
 * <p>Every request carries {@code X-Forwarded-For}, which the test profile
 * trusts from loopback, so each instance is its own client IP to the
 * per-IP rate limits. A plain {@code new LocalHttp(port)} picks a random
 * address in 198.18.0.0/15 (reserved for benchmarking, never routed), so
 * tests do not share one bucket and exhaust it for each other.
 */
public final class LocalHttp {

    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;

    private final String forwardedFor;

    public LocalHttp(int port) {
        this(port, randomClientIp());
    }

    /** @param forwardedFor the X-Forwarded-For value to send, a single IP or a proxy chain */
    public LocalHttp(int port, String forwardedFor) {
        this.baseUrl = "http://localhost:" + port;
        this.forwardedFor = forwardedFor;
    }

    private static String randomClientIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198." + (18 + random.nextInt(2)) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    public HttpResponse<String> get(String path, String... headerNameValuePairs) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET(), headerNameValuePairs);
    }

    public HttpResponse<String> postJson(String path, String json, String... headerNameValuePairs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return send(builder, headerNameValuePairs);
    }

    public HttpResponse<String> options(String path, String... headerNameValuePairs) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()), headerNameValuePairs);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String... headerNameValuePairs) {
        builder.header(FORWARDED_FOR, forwardedFor);
        if (headerNameValuePairs.length > 0) {
            builder.headers(headerNameValuePairs);
        }
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call to the test server failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the test server", e);
        }
    }
}
