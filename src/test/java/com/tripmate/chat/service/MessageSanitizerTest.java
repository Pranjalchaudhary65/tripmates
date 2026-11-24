package com.tripmate.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSanitizerTest {

    @Test
    void plainTextIsKeptAsItIs() {
        assertThat(MessageSanitizer.clean("Hi! Are you up for a slow trip? 🙂")).isEqualTo("Hi! Are you up for a slow trip? 🙂");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<b>bold</b> move|bold move",
            "<img src=x onerror=alert(1)>hello|hello",
            "<script>alert(1)</script>after|after",
            "<style>body{display:none}</style>visible|visible",
            "<a href=\"javascript:alert(1)\">click</a>|click",
            "<svg onload=alert(1)><text>x</text></svg>ok|ok",
            "&lt;b&gt;not a tag&lt;/b&gt;|<b>not a tag</b>"})
    void markupIsDroppedAndOnlyTextSurvives(String raw, String expected) {
        assertThat(MessageSanitizer.clean(raw)).isEqualTo(expected);
    }

    @Test
    void lessThanThatIsNotATagStays() {
        assertThat(MessageSanitizer.clean("I <3 Goa")).isEqualTo("I <3 Goa");
        assertThat(MessageSanitizer.clean("2 < 5 days")).isEqualTo("2 < 5 days");
    }

    @Test
    void directionOverridesZeroWidthSpacesAndControlsAreRemoved() {
        String spoof = "invoice‮gpj.exe";
        assertThat(MessageSanitizer.clean(spoof)).isEqualTo("invoicegpj.exe");
        assertThat(MessageSanitizer.clean("a​b⁦c⁩d﻿e\u0007f")).isEqualTo("abcdef");
    }

    @Test
    void joinersInsideEmojiAndDevanagariAreKept() {
        String family = "👨‍👩‍👧";
        String hindi = "क्‍ष";
        assertThat(MessageSanitizer.clean(family)).isEqualTo(family);
        assertThat(MessageSanitizer.clean(hindi)).isEqualTo(hindi);
    }

    @Test
    void whitespaceIsTidied() {
        assertThat(MessageSanitizer.clean("  line one  \r\n\r\n\r\n\r\nline\ttwo   \n")).isEqualTo("line one\n\nline two");
    }

    @Test
    void textThatIsOnlyMarkupOrInvisibleBecomesEmpty() {
        assertThat(MessageSanitizer.clean("<br><p></p>")).isEmpty();
        assertThat(MessageSanitizer.clean("​‮  ")).isEmpty();
        assertThat(MessageSanitizer.clean(null)).isEmpty();
    }

    @Test
    void decomposedAccentsAreComposed() {
        assertThat(MessageSanitizer.clean("Café")).isEqualTo("Café");
    }
}
