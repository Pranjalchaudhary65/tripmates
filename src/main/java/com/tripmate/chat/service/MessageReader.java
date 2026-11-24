package com.tripmate.chat.service;

import com.tripmate.chat.entity.ChatMessage;
import com.tripmate.chat.entity.MessageKind;
import com.tripmate.chat.repository.ChatMessageRepository;
import com.tripmate.chat.repository.ChatQueries.ConversationCard;
import com.tripmate.chat.repository.ChatQueries.LastMessage;
import com.tripmate.chat.service.ChatViews.InboxItem;
import com.tripmate.chat.service.ChatViews.MessagePage;
import com.tripmate.chat.service.ChatViews.MessageView;
import com.tripmate.identity.crypto.EncryptedField;
import com.tripmate.identity.crypto.UserDataCipher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Opens stored messages for one of the two people in their conversation.
 * Callers have already checked that the viewer is in it; this only decrypts
 * and shapes. A body sealed under a key that has since been shredded reads
 * as null, so one erased account never breaks the other person's history.
 */
@Component
public class MessageReader {

    static final int PREVIEW_LENGTH = 120;

    private final ChatMessageRepository messages;

    private final UserDataCipher cipher;

    public MessageReader(ChatMessageRepository messages, UserDataCipher cipher) {
        this.messages = messages;
        this.cipher = cipher;
    }

    /** Up to {@code size} messages below {@code beforeSeq}, oldest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MessagePage page(UUID viewerId, UUID conversationId, long beforeSeq, int size) {
        List<ChatMessage> newestFirst = messages.findPageBefore(conversationId, beforeSeq, Limit.of(size + 1));
        boolean hasOlder = newestFirst.size() > size;
        List<MessageView> items = new ArrayList<>(Math.min(size, newestFirst.size()));
        for (ChatMessage message : newestFirst.subList(0, Math.min(size, newestFirst.size()))) {
            items.add(view(message, viewerId));
        }
        Collections.reverse(items);
        return new MessagePage(items, hasOlder);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MessageView view(ChatMessage message, UUID viewerId) {
        String body = message.getKind() == MessageKind.USER
                ? open(message.getSenderId(), message.getConversationId(), message.getSeq(),
                message.getBodyCiphertext())
                : null;
        return new MessageView(message.getId(), message.getSeq(), message.getKind(),
                viewerId.equals(message.getSenderId()), body, message.getSystemCode(), message.isRedacted(),
                message.getFlagReason(), message.getCreatedAt());
    }

    /** The same message shaped for someone who already holds its plain text, the sender at send time. */
    public static MessageView view(ChatMessage message, UUID viewerId, String body) {
        return new MessageView(message.getId(), message.getSeq(), message.getKind(),
                viewerId.equals(message.getSenderId()), body, message.getSystemCode(), message.isRedacted(),
                message.getFlagReason(), message.getCreatedAt());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InboxItem inboxItem(ConversationCard card, UUID viewerId) {
        LastMessage last = card.lastMessage();
        if (last == null) {
            return new InboxItem(card, false, null);
        }
        boolean mine = viewerId.equals(last.senderId());
        String preview = last.kind() == MessageKind.USER
                ? preview(open(last.senderId(), card.id(), last.seq(), last.bodyCiphertext()))
                : null;
        return new InboxItem(card, mine, preview);
    }

    /** The plain text of a USER message, or null when it can no longer be opened. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String open(UUID senderId, UUID conversationId, long seq, byte[] ciphertext) {
        return cipher.open(senderId, EncryptedField.CHAT_MESSAGE, ChatMessage.encryptionContext(conversationId, seq),
                        ciphertext)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse(null);
    }

    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        String oneLine = body.replace('\n', ' ');
        if (oneLine.codePointCount(0, oneLine.length()) <= PREVIEW_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, PREVIEW_LENGTH - 1)) + "…";
    }
}
