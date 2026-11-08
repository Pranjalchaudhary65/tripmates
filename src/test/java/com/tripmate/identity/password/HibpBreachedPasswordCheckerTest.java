package com.tripmate.identity.password;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tripmate.identity.password.BreachedPasswordChecker.BreachStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real client against a local HTTP server standing in for the
 * Pwned Passwords API, so nothing leaves the machine and every request the
 * client makes can be inspected.
 */
class HibpBreachedPasswordCheckerTest {

    private static final String PASSWORD = "password";

    /** SHA-1("password"), a published test vector. */
    private static final String PASSWORD_SHA1 = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8";

    private static final String PREFIX = PASSWORD_SHA1.substring(0, 5);

    private static final String SUFFIX = PASSWORD_SHA1.substring(5);

    private final List<RecordedRequest> requests = new ArrayList<>();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sha1MatchesKnownVector() {
        assertThat(HibpBreachedPasswordChecker.sha1Hex(PASSWORD)).isEqualTo(PASSWORD_SHA1);
    }

    @Test
    void onlyTheFiveCharacterPrefixLeavesTheServer() {
        HibpBreachedPasswordChecker checker = checkerFor(200, "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n");

        checker.check(PASSWORD);

        assertThat(requests).hasSize(1);
        RecordedRequest request = requests.getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().getPath()).isEqualTo("/range/" + PREFIX);
        assertThat(request.uri().getQuery()).isNull();
        assertThat(request.body()).isEmpty();
        assertThat(request.addPadding()).isEqualTo("true");
        assertThat(request.uri().toString() + request.body()).doesNotContain(SUFFIX, PASSWORD);
    }

    @Test
    void suffixInTheRangeMeansBreached() {
        String body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n" + SUFFIX + ":9545824\r\n";

        assertThat(checkerFor(200, body).check(PASSWORD)).isEqualTo(BreachStatus.BREACHED);
    }

    @Test
    void suffixMatchIsCaseInsensitive() {
        assertThat(checkerFor(200, SUFFIX.toLowerCase() + ":3").check(PASSWORD)).isEqualTo(BreachStatus.BREACHED);
    }

    @Test
    void paddingEntryWithZeroCountIsNotABreach() {
        assertThat(checkerFor(200, SUFFIX + ":0\r\n").check(PASSWORD)).isEqualTo(BreachStatus.NOT_FOUND);
    }

    @Test
    void absentSuffixIsNotFound() {
        assertThat(checkerFor(200, "0018A45C4D1DEF81644B54AB7F969B88D65:1").check(PASSWORD))
                .isEqualTo(BreachStatus.NOT_FOUND);
    }

    @Test
    void serverErrorIsUnavailable() {
        assertThat(checkerFor(503, "").check(PASSWORD)).isEqualTo(BreachStatus.UNAVAILABLE);
    }

    @Test
    void unreachableHostIsUnavailable() throws IOException {
        HttpServer closed = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = closed.getAddress().getPort();
        closed.stop(0);

        HibpBreachedPasswordChecker checker = new HibpBreachedPasswordChecker(
                new HibpProperties(URI.create("http://127.0.0.1:" + port), Duration.ofSeconds(1)));

        assertThat(checker.check(PASSWORD)).isEqualTo(BreachStatus.UNAVAILABLE);
    }

    @Test
    void slowServerIsUnavailableWithinTheTimeout() throws IOException {
        startServer(exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, SUFFIX + ":1");
        });
        HibpBreachedPasswordChecker checker = new HibpBreachedPasswordChecker(
                new HibpProperties(baseUri(), Duration.ofMillis(300)));

        long started = System.nanoTime();
        BreachStatus status = checker.check(PASSWORD);

        assertThat(status).isEqualTo(BreachStatus.UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    private HibpBreachedPasswordChecker checkerFor(int status, String body) {
        try {
            startServer(exchange -> {
                requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("Add-Padding")));
                respond(exchange, status, body);
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new HibpBreachedPasswordChecker(new HibpProperties(baseUri(), Duration.ofSeconds(2)));
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record RecordedRequest(String method, URI uri, String body, String addPadding) {
    }
}
