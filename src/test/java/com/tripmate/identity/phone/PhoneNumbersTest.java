package com.tripmate.identity.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"+919876543210", "+91 98765 43210", "9876543210", "098765-43210", "919876543210",
            "0091 98765 43210", "(+91) 98765.43210"})
    void everySpellingOfAnIndianMobileNumberBecomesOne(String typed) {
        assertThat(PhoneNumbers.normalise(typed)).hasValue("+919876543210");
    }

    @Test
    void aForeignNumberKeepsItsCountryCode() {
        assertThat(PhoneNumbers.normalise("+44 7700 900123")).hasValue("+447700900123");
        assertThat(PhoneNumbers.normalise("0044 7700 900123")).hasValue("+447700900123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "12345", "5876543210", "+0123456789", "+91 98765 4321a", "+1234567890123456",
            "tel:+919876543210", "+91 9876543210"})
    void anythingElseIsRefused(String typed) {
        assertThat(PhoneNumbers.normalise(typed)).isEmpty();
    }

    @Test
    void nullAndOverlongInputAreRefusedWithoutWork() {
        assertThat(PhoneNumbers.normalise(null)).isEmpty();
        assertThat(PhoneNumbers.normalise("9".repeat(33))).isEmpty();
    }

    @Test
    void theMaskShowsTheCountryCodeAndLastThreeDigitsOnly() {
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91 ••••••• 210");
        assertThat(PhoneNumbers.mask("+447700900123")).isEqualTo("+ ••••••••• 123");
    }
}
