package com.tripmate.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps the columns of {@code users} that registration, email verification
 * and two-factor authentication use. Columns owned by later features (phone,
 * trust score) are left unmapped until those features exist; Hibernate's
 * schema validation only checks mapped columns, and inserts leave the rest to
 * their database defaults.
 *
 * <p>The TOTP secret is held only as ciphertext under the user's data key
 * ({@code identity.crypto.FieldEncryption}); this class never sees it in
 * plain text.
 *
 * <p>State changes go through the intent-named methods below; there are no
 * setters, so a caller cannot, say, mark an email verified without also
 * raising the tier.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Gender gender;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_verification_tier", nullable = false, length = 20)
    private VerificationTier idVerificationTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "encryption_key_id")
    private UUID encryptionKeyId;

    @Column(name = "totp_secret_ciphertext")
    private byte[] totpSecretCiphertext;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "totp_last_used_step")
    private Long totpLastUsedStep;

    protected User() {
    }

    public static User register(String email, String passwordHash, String fullName,
                                LocalDate dateOfBirth, Gender gender, Instant now) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.dateOfBirth = dateOfBirth;
        user.gender = gender;
        user.idVerificationTier = VerificationTier.NONE;
        user.accountStatus = AccountStatus.ACTIVE;
        user.role = Role.USER;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /**
     * Nobody has proven they own this address yet, so the latest registration
     * attempt wins. Whoever set these credentials is the only client holding
     * the verification ID for the code issued alongside them.
     */
    public boolean isPendingEmailVerification() {
        return emailVerifiedAt == null && accountStatus == AccountStatus.ACTIVE && deletedAt == null;
    }

    /** Not suspended, banned or deleted. */
    public boolean isActive() {
        return accountStatus == AccountStatus.ACTIVE && deletedAt == null;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public void replacePendingRegistration(String passwordHash, String fullName,
                                           LocalDate dateOfBirth, Gender gender, Instant now) {
        if (!isPendingEmailVerification()) {
            throw new IllegalStateException("Only an unverified, active account can be re-registered");
        }
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
        this.updatedAt = now;
    }

    public void markEmailVerified(Instant now) {
        this.emailVerifiedAt = now;
        if (idVerificationTier == VerificationTier.NONE) {
            this.idVerificationTier = VerificationTier.EMAIL;
        }
        this.updatedAt = now;
    }

    /** Points the account at the data key its encrypted columns are sealed under, once, when the key is created. */
    public void useEncryptionKey(UUID keyId, Instant now) {
        if (encryptionKeyId != null) {
            throw new IllegalStateException("The account already has a data key");
        }
        this.encryptionKeyId = keyId;
        this.updatedAt = now;
    }

    /**
     * Stores a new, not yet confirmed TOTP secret, replacing any earlier
     * unconfirmed one. Login is unaffected until {@link #enableTotp}.
     */
    public void startTotpEnrolment(byte[] secretCiphertext, Instant now) {
        if (totpEnabled) {
            throw new IllegalStateException("Two-factor authentication is already on");
        }
        this.totpSecretCiphertext = secretCiphertext.clone();
        this.totpLastUsedStep = null;
        this.updatedAt = now;
    }

    /** Turns 2FA on after the first code from the pending secret, whose step can never be used again. */
    public void enableTotp(long confirmedStep, Instant now) {
        if (totpEnabled || totpSecretCiphertext == null) {
            throw new IllegalStateException("No pending two-factor enrolment");
        }
        this.totpEnabled = true;
        this.totpLastUsedStep = confirmedStep;
        this.updatedAt = now;
    }

    /** Records an accepted code's time step. Only a later step is ever accepted after this. */
    public void recordTotpStep(long step, Instant now) {
        if (totpLastUsedStep != null && step <= totpLastUsedStep) {
            throw new IllegalStateException("A TOTP step can only move forward");
        }
        this.totpLastUsedStep = step;
        this.updatedAt = now;
    }

    public void disableTotp(Instant now) {
        this.totpEnabled = false;
        this.totpSecretCiphertext = null;
        this.totpLastUsedStep = null;
        this.updatedAt = now;
    }

    /**
     * 2FA on, or an ADMIN. ck_users_admin_has_two_factor makes the second
     * part redundant today; it is here so that if the constraint were ever
     * dropped, an admin without TOTP would be unable to sign in at all
     * rather than signed in on a password alone.
     */
    public boolean requiresSecondFactor() {
        return totpEnabled || role == Role.ADMIN;
    }

    /** An unconfirmed secret is waiting for its first code. */
    public boolean hasPendingTotpEnrolment() {
        return !totpEnabled && totpSecretCiphertext != null;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public VerificationTier getIdVerificationTier() {
        return idVerificationTier;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getEncryptionKeyId() {
        return encryptionKeyId;
    }

    /** Ciphertext only; a copy, so the entity's own array cannot be changed through it. */
    public byte[] getTotpSecretCiphertext() {
        return totpSecretCiphertext == null ? null : totpSecretCiphertext.clone();
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public Long getTotpLastUsedStep() {
        return totpLastUsedStep;
    }
}
