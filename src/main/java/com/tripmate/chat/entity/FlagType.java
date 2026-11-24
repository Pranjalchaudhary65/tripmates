package com.tripmate.chat.entity;

/**
 * What a moderation flag says a message does. Values match
 * ck_moderation_flags_type and ck_chat_messages_flag_reason.
 */
public enum FlagType {

    /** Pushing the other person to leave the app: another messenger, a phone number, meeting somewhere private. */
    OFF_PLATFORM_PUSH,

    /** Asking for money, a deposit or payment details before anyone has met. */
    MONEY_REQUEST,

    /** Abuse or threats. */
    HARASSMENT
}
