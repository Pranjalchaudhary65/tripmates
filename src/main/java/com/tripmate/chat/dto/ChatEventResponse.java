package com.tripmate.chat.dto;

import java.util.UUID;

/**
 * What the socket pushes to {@code /user/queue/chat}. Every event names its
 * conversation; the client already holds everything else about it.
 *
 * @param message     MESSAGE only
 * @param unreadCount MESSAGE and READ: the receiver's unread count in this conversation after the event
 */
public record ChatEventResponse(Type type, UUID conversationId, ChatMessageResponse message, Long unreadCount) {

    public enum Type {
        /** A new message, to both people; each gets it from their own side. */
        MESSAGE,
        /** The receiver read up to a point in another tab or device. */
        READ,
        /** The other person is typing. */
        TYPING
    }
}
