package com.tripmate.chat.entity;

/**
 * Values match ck_chat_messages_kind. A USER message was written by one of
 * the two people in the conversation; a SYSTEM message by nobody, and it is
 * stored as a fixed code rather than text, so it can never be mistaken for
 * something either person said.
 */
public enum MessageKind {
    USER, SYSTEM
}
