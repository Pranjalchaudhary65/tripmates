package com.tripmate.chat.service;

import com.tripmate.platform.error.ProblemType;
import org.springframework.http.HttpStatus;

/** Errors from the masked chat. Every detail is a fixed sentence; none echoes the message or the request. */
public enum ChatProblem implements ProblemType {

    EMPTY_MESSAGE(HttpStatus.BAD_REQUEST, "empty_message", "Write something before sending.", "body"),

    MESSAGE_TOO_LONG(HttpStatus.BAD_REQUEST, "message_too_long",
            "Keep a message under 2000 characters. Split a longer one in two.", "body"),

    /** No new messages: the conversation was closed, or the other person's account is no longer active. */
    CONVERSATION_CLOSED(HttpStatus.CONFLICT, "conversation_closed", "This conversation is closed.", null),

    /** A retried send reused its message ID for different text. */
    MESSAGE_ID_REUSED(HttpStatus.CONFLICT, "message_id_reused",
            "This message ID was already used for a different message.", null),

    CONTACT_LOCKED(HttpStatus.CONFLICT, "contact_locked",
            "Contact details are shared once the trip is locked, after you have met in person.", null);

    private final HttpStatus status;

    private final String code;

    private final String detail;

    private final String field;

    ChatProblem(HttpStatus status, String code, String detail, String field) {
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.field = field;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public String field() {
        return field;
    }
}
