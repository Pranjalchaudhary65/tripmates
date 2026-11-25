package com.tripmate.platform.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingLogCustomizerTest {

    @Test
    void emailsKeepOnlyTheirFirstCharacterAndDomain() {
        assertThat(PiiMaskingLogCustomizer.mask("Login failed for asha.rao+trips@example.co.in today"))
                .isEqualTo("Login failed for a***@example.co.in today");
    }

    @Test
    void phoneNumbersKeepOnlyTheirLastTwoDigits() {
        assertThat(PiiMaskingLogCustomizer.mask("SMS to +91 98765 43210 failed")).isEqualTo("SMS to **********10 failed");
        assertThat(PiiMaskingLogCustomizer.mask("number 9876543210")).isEqualTo("number **********10");
    }

    @Test
    void idsTimestampsAndShortNumbersAreLeftAlone() {
        String line = "request 3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b at 2026-11-25T10:00:00.123456Z took 1234 ms, "
                + "seq 42, amount 360000";
        assertThat(PiiMaskingLogCustomizer.mask(line)).isEqualTo(line);
    }
}
