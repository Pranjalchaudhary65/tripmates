package com.tripmate.moderation.service;

/** Values match ck_reports_reason. A fixed list, so a report's category is never free text. */
public enum ReportReason {
    HARASSMENT, SCAM_OR_MONEY_REQUEST, FAKE_PROFILE, UNSAFE_BEHAVIOUR, OTHER
}
