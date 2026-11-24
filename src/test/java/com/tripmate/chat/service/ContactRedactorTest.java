package com.tripmate.chat.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContactRedactorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "mail me at asha.k+trips@gmail.com ok",
            "pay on asha@okaxis please",
            "9876543210@ybl is my upi",
            "call 9876543210",
            "call +91 98765 43210",
            "call (080) 2345-6789 tonight",
            "call 98-76-54-32-10",
            "see https://example.com/me",
            "see www.example.com",
            "wa.me/919876543210",
            "t.me/ashatravels",
            "instagram.com/asha.travels",
            "find me @asha.travels"})
    void contactDetailsAreReplaced(String text) {
        ContactRedactor.Redaction redaction = ContactRedactor.redact(text);

        assertThat(redaction.redacted()).as(text).isTrue();
        assertThat(redaction.text()).contains(ContactRedactor.PLACEHOLDER);
        assertThat(redaction.text()).doesNotContainPattern("\\d{5}");
        assertThat(redaction.text()).doesNotContain("@okaxis", "gmail", "example.com", "asha.travels", "ashatravels");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "We land on 2026-12-01 at 10:30",
            "Budget is 15000 to 20000 each",
            "Room 304, floor 3",
            "Rs 1,500 per night",
            "booking.com has good deals",
            "I <3 Goa",
            "5 of us @ the beach"})
    void ordinaryTripTalkIsLeftAlone(String text) {
        ContactRedactor.Redaction redaction = ContactRedactor.redact(text);

        assertThat(redaction.redacted()).as(text).isFalse();
        assertThat(redaction.text()).isEqualTo(text);
    }
}
