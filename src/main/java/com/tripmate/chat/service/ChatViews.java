package com.tripmate.chat.service;

import com.tripmate.chat.entity.FlagType;
import com.tripmate.chat.entity.MessageKind;
import com.tripmate.chat.entity.SystemCode;
import com.tripmate.chat.repository.ChatQueries.ConversationCard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the chat services hand to controllers and to the socket push. No
 * user ID appears in any of them: in a conversation of two, "mine" or "not
 * mine" is all a client needs to know about who wrote a message.
 */
public final class ChatViews {

    private ChatViews() {
    }

    /**
     * One message as one of the two people sees it.
     *
     * @param body       the stored text; null for a SYSTEM message, and for a
     *                   message whose writer's data has been erased
     * @param flag       the moderation flag both sides are warned about, or null
     */
    public record MessageView(UUID id, long seq, MessageKind kind, boolean mine, String body, SystemCode systemCode,
                              boolean redacted, FlagType flag, Instant sentAt) {
    }

    /** @param hasOlder whether messages older than the first one here exist */
    public record MessagePage(List<MessageView> items, boolean hasOlder) {
    }

    /**
     * A conversation in the inbox.
     *
     * @param lastMessagePreview the start of the last message's text; null for
     *                           none, a SYSTEM message or an unreadable one
     */
    public record InboxItem(ConversationCard card, boolean lastMessageMine, String lastMessagePreview) {
    }

    /** The conversation screen: its header and the newest page of messages. */
    public record ConversationView(InboxItem conversation, MessagePage messages) {
    }

    /** A sent message as its sender sees it; {@code replayed} when it was a retry of one already stored. */
    public record Sent(MessageView message, boolean replayed) {
    }
}
