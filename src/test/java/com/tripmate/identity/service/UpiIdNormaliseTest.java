package com.tripmate.identity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpiIdNormaliseTest {

    @Test
    void aUpiIdIsTrimmedAndLowerCased() {
        assertThat(UpiIdService.normalise("  Asha.Rao-1@OkBank ")).hasValue("asha.rao-1@okbank");
        assertThat(UpiIdService.normalise("9876543210@ybl")).hasValue("9876543210@ybl");
    }

    @Test
    void anythingThatCouldBreakOutOfAPaymentLinkIsRefused() {
        assertThat(UpiIdService.normalise("asha@okbank&am=1")).isEmpty();
        assertThat(UpiIdService.normalise("asha@okbank?x")).isEmpty();
        assertThat(UpiIdService.normalise("as ha@okbank")).isEmpty();
        assertThat(UpiIdService.normalise("asha@@okbank")).isEmpty();
        assertThat(UpiIdService.normalise("asha")).isEmpty();
        assertThat(UpiIdService.normalise("@okbank")).isEmpty();
        assertThat(UpiIdService.normalise("asha@1bank")).isEmpty();
        assertThat(UpiIdService.normalise(null)).isEmpty();
    }
}
