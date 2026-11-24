package com.tripmate.chat.entity;

/**
 * What was done automatically about a flag. Values match
 * ck_moderation_flags_action. AUTO_SUSPEND exists for a reviewed moderation
 * case; no pattern match on its own ever suspends an account.
 */
public enum AutoAction {
    NONE, TRUST_SCORE_PENALTY, AUTO_SUSPEND
}
