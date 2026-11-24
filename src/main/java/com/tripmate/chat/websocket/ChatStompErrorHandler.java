package com.tripmate.chat.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

/**
 * The ERROR frame sent when a client frame is refused or fails. Spring's
 * default copies the exception message into the frame; this one always says
 * the same thing, so a refusal never tells a client whether its token was
 * bad, its session ended or its destination was wrong, and no internal
 * exception text ever leaves the server.
 */
public class ChatStompErrorHandler extends StompSubProtocolErrorHandler {

    static final String MESSAGE = "Not allowed";

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(MESSAGE);
        accessor.setLeaveMutable(true);
        if (clientMessage != null) {
            StompHeaderAccessor client = StompHeaderAccessor.wrap(clientMessage);
            if (client.getReceipt() != null) {
                accessor.setReceiptId(client.getReceipt());
            }
        }
        MessageHeaders headers = accessor.getMessageHeaders();
        return MessageBuilder.createMessage(MESSAGE.getBytes(StandardCharsets.UTF_8), headers);
    }
}
