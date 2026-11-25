package com.tripmate.settlement.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpiLinksTest {

    @Test
    void aLinkCarriesThePayeeTheAmountInRupeesAndAFixedNote() {
        assertThat(UpiLinks.payLink("asha.rao@okbank", "Asha", 120_005))
                .isEqualTo("upi://pay?pa=asha.rao@okbank&pn=Asha&am=1200.05&cu=INR&tn=TripMates%20settle-up");
    }

    @Test
    void aNameCannotAddParametersToTheLink() {
        String link = UpiLinks.payLink("asha@okbank", "Asha&am=1&pa=thief@bank", 5_000);

        assertThat(link).contains("pn=Asha%26am%3D1%26pa%3Dthief%40bank").contains("&am=50.00&");
        assertThat(link.split("&pa=", -1)).hasSize(1);
    }

    @Test
    void rupeesAlwaysHaveTwoDecimals() {
        assertThat(UpiLinks.rupees(1)).isEqualTo("0.01");
        assertThat(UpiLinks.rupees(100)).isEqualTo("1.00");
        assertThat(UpiLinks.rupees(100_000_000)).isEqualTo("1000000.00");
    }

    @Test
    void aZeroAmountHasNoLink() {
        assertThatThrownBy(() -> UpiLinks.payLink("asha@okbank", "Asha", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
