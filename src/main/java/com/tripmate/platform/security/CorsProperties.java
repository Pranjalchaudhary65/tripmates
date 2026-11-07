package com.tripmate.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

/**
 * Browser origins allowed to call the API cross-origin, with credentials.
 *
 * <p>Checked once at startup, so a bad value stops the app instead of
 * opening it up: every entry must be an exact {@code scheme://host[:port]}
 * origin. Wildcards are refused outright - with credentials allowed, any
 * origin that can call the API can do so as the logged-in user. Plain
 * {@code http} is accepted only for loopback hosts (the local Vite dev
 * server); anywhere else, a network attacker could inject script into the
 * allowed page and inherit its access.
 */
@ConfigurationProperties("tripmate.cors")
public record CorsProperties(List<String> allowedOrigins) {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("tripmate.cors.allowed-origins must list at least one origin");
        }
        allowedOrigins = allowedOrigins.stream().map(String::strip).map(CorsProperties::requireExactOrigin).toList();
    }

    private static String requireExactOrigin(String origin) {
        if (origin.isEmpty() || origin.contains("*")) {
            throw invalid(origin, "wildcards and empty entries are not allowed");
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            throw invalid(origin, "not a valid URI");
        }
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw invalid(origin, "scheme must be http or https");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw invalid(origin, "must be scheme://host[:port]");
        }
        if (!uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid(origin, "must not have a path, trailing slash, query or fragment");
        }
        if ("http".equals(scheme) && !LOOPBACK_HOSTS.contains(uri.getHost())) {
            throw invalid(origin, "plain http is only allowed for localhost");
        }
        return origin;
    }

    private static IllegalArgumentException invalid(String origin, String reason) {
        return new IllegalArgumentException(
                "tripmate.cors.allowed-origins entry '" + origin + "' rejected: " + reason);
    }
}
