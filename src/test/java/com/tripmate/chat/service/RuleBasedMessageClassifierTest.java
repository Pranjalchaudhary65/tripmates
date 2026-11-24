package com.tripmate.chat.service;

import com.tripmate.chat.entity.FlagType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedMessageClassifierTest {

    private final RuleBasedMessageClassifier classifier = new RuleBasedMessageClassifier();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Send me 2000 on gpay as advance|MONEY_REQUEST|0.950",
            "pls transfer to my account rs 5000 for booking|MONEY_REQUEST|0.950",
            "Just pay the token amount and it is confirmed|MONEY_REQUEST|0.900",
            "Let's talk on WhatsApp instead|OFF_PLATFORM_PUSH|0.800",
            "We can skip the meet-up, I am genuine|OFF_PLATFORM_PUSH|0.900",
            "Come to my hotel room first|OFF_PLATFORM_PUSH|0.900",
            "I will find you|HARASSMENT|0.950",
            "send me your private pics|HARASSMENT|0.950"})
    void flagsTheShapesItWatchesFor(String text, FlagType type, String confidence) {
        List<MessageClassifier.Label> labels = classifier.classify(text, false);

        assertThat(labels).as(text).isNotEmpty();
        assertThat(labels).as(text).contains(new MessageClassifier.Label(type, new BigDecimal(confidence)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Send me the itinerary for 2026 when you can",
            "I'll pay 1500 for the hostel, you get dinner",
            "I booked in advance, me and my friend are set",
            "Shall we split the cab from the airport?",
            "The beach at sunset was unreal"})
    void leavesOrdinaryTripTalkAlone(String text) {
        assertThat(classifier.classify(text, false)).as(text).isEmpty();
    }

    @Test
    void aRedactedContactDetailIsAnOffPlatformPush() {
        assertThat(classifier.classify("here it is", true))
                .containsExactly(new MessageClassifier.Label(FlagType.OFF_PLATFORM_PUSH, new BigDecimal("0.900")));
    }

    @Test
    void keepsOnlyTheStrongestLabelOfEachTypeStrongestFirst() {
        List<MessageClassifier.Label> labels = classifier.classify(
                "whatsapp me, my number is below, and send me 3000 rupees", true);

        assertThat(labels).extracting(MessageClassifier.Label::type)
                .containsExactly(FlagType.MONEY_REQUEST, FlagType.OFF_PLATFORM_PUSH);
        assertThat(labels.get(1).confidence()).isEqualByComparingTo("0.900");
    }
}
