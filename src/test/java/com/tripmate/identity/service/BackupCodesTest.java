package com.tripmate.identity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupCodesTest {

    @Test
    void aSetIsTenDistinctCodesOfTenCrockfordCharacters() {
        List<String> codes = BackupCodes.generate();

        assertThat(codes).hasSize(10).allMatch(code -> code.matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}"));
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allSatisfy(code -> assertThat(BackupCodes.normalise(code)).hasValue(code.replace("-", "")));
    }

    @Test
    void readsLookAlikesCaseSpacesAndTheHyphenTheWayAPersonMeansThem() {
        assertThat(BackupCodes.normalise("7kq2m-x9pda")).hasValue("7KQ2MX9PDA");
        assertThat(BackupCodes.normalise("7KQ2M X9PDA")).hasValue("7KQ2MX9PDA");
        assertThat(BackupCodes.normalise("O1ILM-XXXXX")).hasValue("0111MXXXXX");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "7KQ2M-X9PD", "7KQ2M-X9PDAB", "7KQ2M-X9PDU", "7KQ2M_X9PDA", "123456"})
    void refusesWhatCannotBeABackupCode(String candidate) {
        assertThat(BackupCodes.normalise(candidate)).isEmpty();
    }
}
