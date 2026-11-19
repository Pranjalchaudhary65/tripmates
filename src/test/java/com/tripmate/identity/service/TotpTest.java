package com.tripmate.identity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** The SHA-1 seed both RFC 4226 Appendix D and RFC 6238 Appendix B use. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);

    @ParameterizedTest
    @CsvSource({
            "0, 755224", "1, 287082", "2, 359152", "3, 969429", "4, 338314",
            "5, 254676", "6, 287922", "7, 162583", "8, 399871", "9, 520489",
    })
    void matchesTheHotpTestVectorsOfRfc4226(long counter, String expected) {
        assertThat(Totp.codeAt(RFC_SECRET, counter)).isEqualTo(expected);
    }

    /**
     * RFC 6238 lists 8-digit codes; a 6-digit code is the same truncated value
     * mod 10^6, so it is the last six digits of each.
     */
    @ParameterizedTest
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130",
    })
    void matchesTheTotpTestVectorsOfRfc6238(long unixSeconds, String eightDigits) {
        Instant at = Instant.ofEpochSecond(unixSeconds);

        assertThat(Totp.codeAt(RFC_SECRET, Totp.stepAt(at))).isEqualTo(eightDigits.substring(2));
        assertThat(Totp.verify(RFC_SECRET, eightDigits.substring(2), at, null))
                .hasValue(Totp.stepAt(at));
    }

    @Test
    void acceptsOneStepEitherSideOfNowAndNothingFurther() {
        long now = Totp.stepAt(NOW);

        for (long offset = -1; offset <= 1; offset++) {
            assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + offset), NOW, null))
                    .as("offset %d", offset).hasValue(now + offset);
        }
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, now - 2), NOW, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + 2), NOW, null)).isEmpty();
    }

    @Test
    void refusesTheLastUsedStepAndAnyEarlierOne() {
        long now = Totp.stepAt(NOW);
        String current = Totp.codeAt(RFC_SECRET, now);
        String previous = Totp.codeAt(RFC_SECRET, now - 1);

        assertThat(Totp.verify(RFC_SECRET, current, NOW, now)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, previous, NOW, now)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, current, NOW, now - 1)).hasValue(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + 1), NOW, now)).hasValue(now + 1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "12345", "1234567", "12a456", " 123456",
            "１２３４５６"})
    void refusesAnythingButSixAsciiDigits(String candidate) {
        assertThat(Totp.verify(RFC_SECRET, candidate, NOW, null)).isEqualTo(OptionalLong.empty());
    }

    @Test
    void generatesA160BitSecretThatIsDifferentEachTime() {
        byte[] first = Totp.generateSecret();

        assertThat(first).hasSize(20);
        assertThat(Totp.generateSecret()).isNotEqualTo(first);
    }
}
