package com.tripmate.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Real HTTP calls against the running test server, through Tomcat and the
 * full servlet filter chain. MockMvc skips the container, so it cannot see
 * the forward to /error or headers set by filters outside Spring MVC.
 */
public final class LocalHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;

    public LocalHttp(int port) {
        this.baseUrl = "http://localhost:" + port;
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
        if (headerNameValuePairs.length > 0) {
            builder.headers(headerNameValuePairs);
        }
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call to the test server failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the test server", e);
        }
    }
}
