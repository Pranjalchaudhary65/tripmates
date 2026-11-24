package com.tripmate.chat.websocket;

import com.tripmate.chat.service.ConversationService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * The one thing a client sends over the socket: "I am typing" in a
 * conversation. It is passed to the other person only when the sender is in
 * that conversation and it is open; otherwise it is dropped without an
 * answer, so the socket cannot be used to probe which conversation IDs exist.
 */
@Controller
public class ChatSocketController {

    public static final String TYPING = "/conversations/{conversationId}/typing";

    private final ConversationService conversations;

    private final ChatPush push;

    public ChatSocketController(ConversationService conversations, ChatPush push) {
        this.conversations = conversations;
        this.push = push;
    }

    @MessageMapping(TYPING)
    public void typing(@DestinationVariable UUID conversationId, Principal principal) {
        if (!(principal instanceof ChatPrincipal caller)) {
            return;
        }
        conversations.typingRecipient(caller.userId(), conversationId)
                .ifPresent(recipient -> push.typing(recipient, conversationId));
    }
}
