package com.tripmate.settlement.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseTextTest {

    @Test
    void whitespaceIsCollapsedAndTrimmed() {
        assertThat(ExpenseText.clean("  Dinner \n at   the shack ")).hasValue("Dinner at the shack");
    }

    @Test
    void directionOverridesAndInvisibleCharactersAreRemoved() {
        assertThat(ExpenseText.clean("Hotel ‮005‬")).hasValue("Hotel 005");
        assertThat(ExpenseText.clean("Taxi​ fare")).hasValue("Taxi fare");
    }

    @Test
    void nothingVisibleOrTooLongIsRefused() {
        assertThat(ExpenseText.clean(" ​ ")).isEmpty();
        assertThat(ExpenseText.clean(null)).isEmpty();
        assertThat(ExpenseText.clean("x".repeat(121))).isEmpty();
        assertThat(ExpenseText.clean("x".repeat(120))).isPresent();
    }
}
