package com.tripmate.chat.websocket;

import com.tripmate.chat.dto.ChatEventResponse;
import com.tripmate.chat.dto.ChatMapper;
import com.tripmate.chat.service.ChatEvents.MessagePosted;
import com.tripmate.chat.service.ChatEvents.ReadMoved;
import com.tripmate.chat.service.ChatEvents.SystemPosted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Delivers chat events to the people they concern, through each user's own
 * queue. Runs only after the change has committed, so nobody is ever shown a
 * message that then rolled back. The recipients are always the two people
 * on the conversation row, chosen here on the server; nothing a client
 * subscribes to can widen that.
 *
 * <p>A push is best effort. A failed push loses nothing: the message is
 * stored, and the client refetches the conversation when it reconnects.
 */
@Component
public class ChatPush {

    static final String QUEUE = "/queue/chat";

    private static final Logger log = LoggerFactory.getLogger(ChatPush.class);

    private final SimpMessagingTemplate template;

    private final ChatMapper mapper;

    public ChatPush(SimpMessagingTemplate template, ChatMapper mapper) {
        this.template = template;
        this.mapper = mapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void messagePosted(MessagePosted posted) {
        send(posted.senderId(), new ChatEventResponse(ChatEventResponse.Type.MESSAGE, posted.conversationId(),
                mapper.toResponse(posted.forSender()), 0L));
        send(posted.recipientId(), new ChatEventResponse(ChatEventResponse.Type.MESSAGE, posted.conversationId(),
                mapper.toResponse(posted.forRecipient()), posted.recipientUnread()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void systemPosted(SystemPosted posted) {
        send(posted.hostUserId(), new ChatEventResponse(ChatEventResponse.Type.MESSAGE, posted.conversationId(),
                mapper.toResponse(posted.message()), posted.hostUnread()));
        send(posted.requesterUserId(), new ChatEventResponse(ChatEventResponse.Type.MESSAGE,
                posted.conversationId(), mapper.toResponse(posted.message()), posted.requesterUnread()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void readMoved(ReadMoved moved) {
        send(moved.readerId(), new ChatEventResponse(ChatEventResponse.Type.READ, moved.conversationId(), null,
                moved.readerUnread()));
    }

    public void typing(UUID recipientId, UUID conversationId) {
        send(recipientId, new ChatEventResponse(ChatEventResponse.Type.TYPING, conversationId, null, null));
    }

    private void send(UUID userId, ChatEventResponse event) {
        try {
            template.convertAndSendToUser(userId.toString(), QUEUE, event);
        } catch (MessagingException e) {
            log.warn("Chat push failed: {}", e.getClass().getSimpleName());
        }
    }
}
