package com.tripmate.identity.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checks a password against Have I Been Pwned's Pwned Passwords corpus using
 * its k-anonymity range API: only the first five hex characters of the
 * password's SHA-1 leave the server. The API answers with every suffix
 * sharing that prefix (hundreds of them), and the match happens locally, so
 * HIBP never learns which password, or even which full hash, was checked.
 *
 * <p>{@code Add-Padding} makes every response a similar size, so someone
 * watching the encrypted traffic cannot narrow down the prefix from the
 * response length. Padding entries carry a count of 0 and are ignored.
 */
@Component
@EnableConfigurationProperties(HibpProperties.class)
public class HibpBreachedPasswordChecker implements BreachedPasswordChecker {

    static final int PREFIX_LENGTH = 5;

    private static final Logger log = LoggerFactory.getLogger(HibpBreachedPasswordChecker.class);

    private final HttpClient client;

    private final HibpProperties properties;

    public HibpBreachedPasswordChecker(HibpProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public BreachStatus check(String password) {
        String hash = sha1Hex(password);
        String prefix = hash.substring(0, PREFIX_LENGTH);
        String suffix = hash.substring(PREFIX_LENGTH);

        HttpRequest request = HttpRequest.newBuilder(rangeUri(prefix))
                .timeout(properties.timeout())
                .header("Add-Padding", "true")
                .header("User-Agent", "TripMates-Server")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Pwned Passwords range API answered HTTP {}", response.statusCode());
                return BreachStatus.UNAVAILABLE;
            }
            return containsSuffix(response.body(), suffix) ? BreachStatus.BREACHED : BreachStatus.NOT_FOUND;
        } catch (IOException e) {
            log.warn("Pwned Passwords range API unreachable: {}", e.getClass().getSimpleName());
            return BreachStatus.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BreachStatus.UNAVAILABLE;
        }
    }

    private URI rangeUri(String prefix) {
        String base = properties.baseUrl().toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "range/" + prefix);
    }

    /** Lines are {@code SUFFIX:COUNT}; a count of 0 marks a padding line, not a real breach. */
    static boolean containsSuffix(String body, String suffix) {
        for (String line : body.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon != suffix.length() || !line.regionMatches(true, 0, suffix, 0, colon)) {
                continue;
            }
            String count = line.substring(colon + 1).trim();
            if (!count.isEmpty() && !count.chars().allMatch(c -> c == '0')) {
                return true;
            }
        }
        return false;
    }

    static String sha1Hex(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is a mandatory JDK algorithm", e);
        }
    }
}
