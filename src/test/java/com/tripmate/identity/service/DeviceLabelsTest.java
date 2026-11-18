package com.tripmate.identity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceLabelsTest {

    private static final Set<String> BROWSERS = Set.of("Edge", "Opera", "Firefox", "Chrome", "Safari", "Browser");

    private static final Set<String> SYSTEMS = Set.of("Windows", "Android", "iOS", "ChromeOS", "macOS", "Linux");

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 | Chrome on Windows
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0 | Edge on Windows
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 OPR/115.0.0.0 | Opera on Windows
            Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0 | Firefox on Linux
            Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15 | Safari on macOS
            Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1 | Safari on iOS
            Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1 | Chrome on iOS
            Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 | Chrome on Android
            Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 | Chrome on ChromeOS
            Mozilla/5.0 (compatible; Microsoft Something) | Unknown device
            curl/8.9.1 | Unknown device
            """)
    void namesCommonBrowsers(String userAgent, String label) {
        assertThat(DeviceLabels.describe(userAgent)).isEqualTo(label);
    }

    @Test
    void missingUserAgentIsAnUnknownDevice() {
        assertThat(DeviceLabels.describe(null)).isEqualTo(DeviceLabels.UNKNOWN);
        assertThat(DeviceLabels.describe("   ")).isEqualTo(DeviceLabels.UNKNOWN);
    }

    @Test
    void noPartOfAHostileUserAgentEverReachesTheLabel() {
        String label = DeviceLabels.describe("<img src=x onerror=alert(1)> Firefox/1 \"; DROP TABLE users; -- Windows");

        assertThat(label).isEqualTo("Firefox on Windows");
        assertThat(isBuiltFromFixedNames(label)).isTrue();
    }

    private static boolean isBuiltFromFixedNames(String label) {
        if (label.equals(DeviceLabels.UNKNOWN) || BROWSERS.contains(label)) {
            return true;
        }
        String[] parts = label.split(" on ");
        return parts.length == 2 && BROWSERS.contains(parts[0]) && SYSTEMS.contains(parts[1]);
    }
}
