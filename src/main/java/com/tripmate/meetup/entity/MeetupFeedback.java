package com.tripmate.meetup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * A private comfort rating after a completed meet-up (DESIGN.md §5
 * meetup_feedback). Not a review: it is never shown to anyone, never leaves
 * this module, and is read for one decision only, whether the match goes on.
 * Written once; there is no editing it after the decision has been taken.
 */
@Entity
@Immutable
@Table(name = "meetup_feedback")
public class MeetupFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meetup_id", nullable = false, updatable = false)
    private UUID meetupId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "comfort_rating", nullable = false, updatable = false)
    private short comfortRating;

    @Column(name = "notes_ciphertext", updatable = false)
    private byte[] notesCiphertext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MeetupFeedback() {
    }

    public static MeetupFeedback record(UUID meetupId, UUID userId, int comfortRating, byte[] notesCiphertext,
                                        Instant now) {
        if (comfortRating < 1 || comfortRating > 5) {
            throw new IllegalArgumentException("Comfort rating is 1 to 5");
        }
        MeetupFeedback feedback = new MeetupFeedback();
        feedback.meetupId = meetupId;
        feedback.userId = userId;
        feedback.comfortRating = (short) comfortRating;
        feedback.notesCiphertext = notesCiphertext;
        feedback.createdAt = now;
        return feedback;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getComfortRating() {
        return comfortRating;
    }
}
