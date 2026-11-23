package com.tripmate.vibe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The user's hard filters: matching drops a trip or a person that breaks one
 * before any score is worked out (DESIGN.md §2 deal_breakers).
 */
@Entity
@Table(name = "deal_breakers")
public class DealBreakers {

    public static final int MAX_TRUST_SCORE = 100;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HabitPreference smoking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HabitPreference drinking;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender_preference", nullable = false, length = 10)
    private GenderPreference genderPreference;

    @Column(name = "min_trust_score", nullable = false)
    private short minTrustScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DealBreakers() {
    }

    public static DealBreakers create(UUID userId, Instant now) {
        DealBreakers dealBreakers = new DealBreakers();
        dealBreakers.userId = userId;
        dealBreakers.createdAt = now;
        return dealBreakers;
    }

    public void replace(HabitPreference smoking, HabitPreference drinking, GenderPreference genderPreference,
                        int minTrustScore, Instant now) {
        if (minTrustScore < 0 || minTrustScore > MAX_TRUST_SCORE) {
            throw new IllegalArgumentException("minTrustScore must be between 0 and 100");
        }
        this.smoking = smoking;
        this.drinking = drinking;
        this.genderPreference = genderPreference;
        this.minTrustScore = (short) minTrustScore;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public HabitPreference getSmoking() {
        return smoking;
    }

    public HabitPreference getDrinking() {
        return drinking;
    }

    public GenderPreference getGenderPreference() {
        return genderPreference;
    }

    public short getMinTrustScore() {
        return minTrustScore;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
