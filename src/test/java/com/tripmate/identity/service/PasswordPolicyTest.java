package com.tripmate.identity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void emailLocalPartInsidePasswordIsPersonalInfo() {
        assertThat(PasswordPolicy.containsPersonalInfo("my-asha.verma-2024!", "Asha.Verma@example.com", "A V"))
                .isTrue();
    }

    @Test
    void namePartInsidePasswordIsPersonalInfoRegardlessOfCase() {
        assertThat(PasswordPolicy.containsPersonalInfo("VERMAfamily2024", "x1@example.com", "Asha Verma"))
                .isTrue();
    }

    @Test
    void shortNamePartsAreIgnoredToAvoidAccidentalMatches() {
        assertThat(PasswordPolicy.containsPersonalInfo("violet-kettle-harbour", "al@example.com", "Al Li"))
                .isFalse();
    }

    @Test
    void unrelatedPasswordPasses() {
        assertThat(PasswordPolicy.containsPersonalInfo("violet-kettle-harbour-91", "asha@example.com", "Asha Verma"))
                .isFalse();
    }
}
