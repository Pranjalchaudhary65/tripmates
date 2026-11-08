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
 * Maps the columns of {@code users} that registration and email verification
 * use. Columns owned by later features (phone, TOTP, trust score, encryption
 * key) are left unmapped until those features exist; Hibernate's schema
 * validation only checks mapped columns, and inserts leave the rest to their
 * database defaults.
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
}
