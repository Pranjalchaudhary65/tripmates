package com.tripmate.chat.entity;

/** Values match ck_conversations_status. A CLOSED conversation keeps its history and takes no new messages. */
public enum ConversationStatus {
    ACTIVE, CLOSED
}
