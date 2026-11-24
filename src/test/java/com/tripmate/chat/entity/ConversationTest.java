package com.tripmate.chat.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    private final UUID host = UUID.randomUUID();

    private final UUID companion = UUID.randomUUID();

    private final Conversation conversation = Conversation.open(UUID.randomUUID(), host, companion, NOW);

    @Test
    void numbersMessagesInOrderAndTheSenderHasReadTheirOwn() {
        assertThat(conversation.nextSeq(host, NOW)).isEqualTo(1);
        assertThat(conversation.nextSeq(companion, NOW)).isEqualTo(2);

        assertThat(conversation.readSeqOf(host)).isEqualTo(1);
        assertThat(conversation.readSeqOf(companion)).isEqualTo(2);
    }

    @Test
    void readMarkersOnlyMoveForwardAndStopAtTheLastMessage() {
        conversation.nextSeq(host, NOW);
        conversation.nextSeq(host, NOW);

        assertThat(conversation.markRead(companion, 99, NOW)).isTrue();
        assertThat(conversation.readSeqOf(companion)).isEqualTo(2);
        assertThat(conversation.markRead(companion, 1, NOW)).isFalse();
        assertThat(conversation.readSeqOf(companion)).isEqualTo(2);
    }

    @Test
    void someoneOutsideTheConversationIsRefusedEverywhere() {
        UUID stranger = UUID.randomUUID();

        assertThat(conversation.includes(stranger)).isFalse();
        assertThatThrownBy(() -> conversation.nextSeq(stranger, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversation.markRead(stranger, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversation.otherThan(stranger)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachSideSeesTheOther() {
        assertThat(conversation.otherThan(host)).isEqualTo(companion);
        assertThat(conversation.otherThan(companion)).isEqualTo(host);
    }

    @Test
    void cannotBeOpenedWithOnePerson() {
        assertThatThrownBy(() -> Conversation.open(UUID.randomUUID(), host, host, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unlockingAndClosingHappenOnce() {
        assertThat(conversation.unlockContact(NOW)).isTrue();
        assertThat(conversation.unlockContact(NOW.plusSeconds(5))).isFalse();
        assertThat(conversation.getContactUnlockedAt()).isEqualTo(NOW);

        assertThat(conversation.close(NOW)).isTrue();
        assertThat(conversation.close(NOW)).isFalse();
        assertThat(conversation.isActive()).isFalse();
    }
}
