package com.tripmate.chat.service;

import com.tripmate.chat.service.ChatViews.MessageView;

import java.util.UUID;

/**
 * What the chat services announce inside their transaction, for the socket
 * push to deliver once it commits. A push never runs for a change that
 * rolled back.
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    /**
     * @param forSender    the message as its sender sees it
     * @param forRecipient the message as the other person sees it
     * @param recipientUnread the other person's unread count in this conversation after it
     */
    public record MessagePosted(UUID conversationId, UUID senderId, UUID recipientId, MessageView forSender,
                                MessageView forRecipient, long recipientUnread) {
    }

    /** The reader's marker moved, so their other open tabs can update their unread count. */
    public record ReadMoved(UUID conversationId, UUID readerId, long readerUnread) {
    }
}
