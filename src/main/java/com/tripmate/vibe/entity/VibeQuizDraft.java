package com.tripmate.vibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Answers to a quiz still in progress, kept on the server so it can be
 * finished later, in another tab or on another device; the client keeps
 * nothing in browser storage.
 */
@Entity
@Table(name = "vibe_quiz_drafts")
public class VibeQuizDraft {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "quiz_version", nullable = false)
    private short quizVersion;

    /** JSON text. Bound untyped (stringtype=unspecified), so PostgreSQL reads it as jsonb. */
    @Column(nullable = false, columnDefinition = "jsonb")
    private String answers;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VibeQuizDraft() {
    }

    public static VibeQuizDraft create(UUID userId) {
        VibeQuizDraft draft = new VibeQuizDraft();
        draft.userId = userId;
        return draft;
    }

    public void save(String answers, int quizVersion, Instant now) {
        this.answers = answers;
        this.quizVersion = (short) quizVersion;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public short getQuizVersion() {
        return quizVersion;
    }

    public String getAnswers() {
        return answers;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
