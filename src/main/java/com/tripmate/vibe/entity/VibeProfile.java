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
 * One user's travel style. Written from a finished quiz, and afterwards
 * either re-written by taking the quiz again or adjusted by hand; there are
 * no setters, so every change says which of the two it is.
 */
@Entity
@Table(name = "vibe_profiles")
public class VibeProfile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_band", nullable = false, length = 20)
    private BudgetBand budgetBand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Pace pace;

    @Enumerated(EnumType.STRING)
    @Column(name = "wake_time", nullable = false, length = 20)
    private WakeTime wakeTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "planning_style", nullable = false, length = 20)
    private PlanningStyle planningStyle;

    @Column(name = "social_energy", nullable = false)
    private short socialEnergy;

    @Column(name = "risk_appetite", nullable = false)
    private short riskAppetite;

    @Column(name = "food_adventurousness", nullable = false)
    private short foodAdventurousness;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_split_style", nullable = false, length = 20)
    private ExpenseSplitStyle expenseSplitStyle;

    /** JSON text. Bound untyped (stringtype=unspecified), so PostgreSQL reads it as jsonb. */
    @Column(name = "raw_answers", nullable = false, columnDefinition = "jsonb")
    private String rawAnswers;

    @Column(name = "quiz_version", nullable = false)
    private short quizVersion;

    @Column(name = "adjusted_at")
    private Instant adjustedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VibeProfile() {
    }

    public static VibeProfile fromQuiz(UUID userId, VibeDimensions dimensions, String rawAnswers, int quizVersion,
                                       Instant now) {
        VibeProfile profile = new VibeProfile();
        profile.userId = userId;
        profile.createdAt = now;
        profile.retakeQuiz(dimensions, rawAnswers, quizVersion, now);
        return profile;
    }

    /** A finished quiz replaces everything, hand adjustments included. */
    public void retakeQuiz(VibeDimensions dimensions, String rawAnswers, int quizVersion, Instant now) {
        apply(dimensions);
        this.rawAnswers = rawAnswers;
        this.quizVersion = (short) quizVersion;
        this.adjustedAt = null;
        this.updatedAt = now;
    }

    /** The user corrected what the quiz worked out. The answers stay as they were given. */
    public void adjust(VibeDimensions dimensions, Instant now) {
        apply(dimensions);
        this.adjustedAt = now;
        this.updatedAt = now;
    }

    private void apply(VibeDimensions dimensions) {
        this.budgetBand = dimensions.budgetBand();
        this.pace = dimensions.pace();
        this.wakeTime = dimensions.wakeTime();
        this.planningStyle = dimensions.planningStyle();
        this.socialEnergy = (short) dimensions.socialEnergy();
        this.riskAppetite = (short) dimensions.riskAppetite();
        this.foodAdventurousness = (short) dimensions.foodAdventurousness();
        this.expenseSplitStyle = dimensions.expenseSplitStyle();
    }

    public VibeDimensions dimensions() {
        return new VibeDimensions(budgetBand, pace, wakeTime, planningStyle, socialEnergy, riskAppetite,
                foodAdventurousness, expenseSplitStyle);
    }

    public UUID getUserId() {
        return userId;
    }

    public BudgetBand getBudgetBand() {
        return budgetBand;
    }

    public Pace getPace() {
        return pace;
    }

    public WakeTime getWakeTime() {
        return wakeTime;
    }

    public PlanningStyle getPlanningStyle() {
        return planningStyle;
    }

    public short getSocialEnergy() {
        return socialEnergy;
    }

    public short getRiskAppetite() {
        return riskAppetite;
    }

    public short getFoodAdventurousness() {
        return foodAdventurousness;
    }

    public ExpenseSplitStyle getExpenseSplitStyle() {
        return expenseSplitStyle;
    }

    public String getRawAnswers() {
        return rawAnswers;
    }

    public short getQuizVersion() {
        return quizVersion;
    }

    public Instant getAdjustedAt() {
        return adjustedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
