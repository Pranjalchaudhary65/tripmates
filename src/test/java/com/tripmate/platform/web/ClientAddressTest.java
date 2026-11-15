package com.tripmate.platform.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressTest {

    @Test
    void anIpv4AddressIsItsOwnKey() {
        assertThat(ClientAddress.parse("203.0.113.9").rateLimitKey()).isEqualTo("203.0.113.9");
    }

    @Test
    void everyAddressInOneIpv6Slash64SharesAKey() {
        String first = ClientAddress.parse("2001:db8:1:2::1").rateLimitKey();
        String other = ClientAddress.parse("2001:db8:1:2:ffff:ffff:ffff:fffe").rateLimitKey();

        assertThat(first).isEqualTo(other).isEqualTo("20010db800010002/64");
        assertThat(ClientAddress.parse("2001:db8:1:3::1").rateLimitKey()).isNotEqualTo(first);
    }

    @Test
    void anIpv4MappedIpv6AddressCountsAsTheIpv4Address() {
        assertThat(ClientAddress.parse("::ffff:203.0.113.9").rateLimitKey()).isEqualTo("203.0.113.9");
    }

    /** Host names and out-of-range quads would otherwise be handed to DNS. */
    @ParameterizedTest
    @ValueSource(strings = {"example.com", "999.1.1.1", "1.2.3", "1.2.3.4.5", "unknown", "1.2.3.-4", "gg::1", ""})
    void anythingButAnIpLiteralIsRejectedWithoutALookup(String value) {
        assertThatThrownBy(() -> ClientAddress.parse(value)).isInstanceOf(IllegalStateException.class);
    }
}
