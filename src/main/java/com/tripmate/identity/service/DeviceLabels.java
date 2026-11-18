package com.tripmate.identity.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a User-Agent header into a short label for the sessions list, such
 * as "Firefox on Linux".
 *
 * <p>The header is written by whoever sends the request, so it is never
 * shown. Every label is built from the fixed names below and nothing else:
 * a User-Agent stuffed with markup, a phishing line or a terminal escape can
 * at worst choose which fixed name appears. A lying User-Agent can make a
 * session look like a different browser, which is why the list also shows
 * the IP address and times, which the client does not choose.
 */
final class DeviceLabels {

    static final String UNKNOWN = "Unknown device";

    /** Order matters: Edge and Opera also say "Chrome", and Chrome also says "Safari". */
    private static final List<Map.Entry<String, String>> BROWSERS = List.of(
            Map.entry("edg/", "Edge"),
            Map.entry("opr/", "Opera"),
            Map.entry("firefox/", "Firefox"),
            Map.entry("fxios/", "Firefox"),
            Map.entry("chrome/", "Chrome"),
            Map.entry("crios/", "Chrome"),
            Map.entry("safari/", "Safari"));

    /** Order matters: Android also says "Linux", and iOS also says "Mac OS X". */
    private static final List<Map.Entry<String, String>> SYSTEMS = List.of(
            Map.entry("windows", "Windows"),
            Map.entry("android", "Android"),
            Map.entry("iphone", "iOS"),
            Map.entry("ipad", "iOS"),
            // With the space: "cros" alone is also inside "microsoft".
            Map.entry("cros ", "ChromeOS"),
            Map.entry("mac os x", "macOS"),
            Map.entry("linux", "Linux"));

    private DeviceLabels() {
    }

    static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String normalised = userAgent.toLowerCase(Locale.ROOT);
        String browser = firstMatch(BROWSERS, normalised);
        String system = firstMatch(SYSTEMS, normalised);
        if (browser == null && system == null) {
            return UNKNOWN;
        }
        if (system == null) {
            return browser;
        }
        return browser == null ? "Browser on " + system : browser + " on " + system;
    }

    private static String firstMatch(List<Map.Entry<String, String>> names, String userAgent) {
        return names.stream()
                .filter(entry -> userAgent.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
