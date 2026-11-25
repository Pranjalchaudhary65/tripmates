package com.tripmate.identity.service;

import com.tripmate.identity.crypto.FieldEncryption;
import com.tripmate.identity.entity.SessionRevocationReason;
import com.tripmate.identity.entity.User;
import com.tripmate.identity.repository.UserRepository;
import com.tripmate.identity.token.SessionPrincipal;
import com.tripmate.identity.upload.ObjectStorage;
import com.tripmate.platform.error.ApiProblemException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Deleting an account (DESIGN.md §9, data_requests DELETION): soft delete
 * plus crypto-shredding. The user row stays (other people's trips, ledgers
 * and reviews point at it) but everything that identifies the person is
 * removed from it: email, name, phone, photo, verification. Every data key
 * is shredded, so everything sealed under it anywhere (phone, TOTP secret,
 * sent chat messages, emergency contact, UPI ID, notes, locations, report
 * details, exports) is unreadable for good. Every session ends.
 */
@Service
public class AccountDeletion {

    private final UserRepository users;

    private final FieldEncryption encryption;

    private final SessionService sessions;

    private final ObjectStorage storage;

    private final PasswordEncoder passwordEncoder;

    private final NamedParameterJdbcTemplate jdbc;

    private final Clock clock;

    public AccountDeletion(UserRepository users, FieldEncryption encryption, SessionService sessions,
                           ObjectStorage storage, PasswordEncoder passwordEncoder, NamedParameterJdbcTemplate jdbc,
                           Clock clock) {
        this.users = users;
        this.encryption = encryption;
        this.sessions = sessions;
        this.storage = storage;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The caller proves it is really them before asking to delete; the same refusal for any wrong password. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requirePassword(SessionPrincipal caller, String password) {
        User user = users.findByIdForUpdate(caller.userId())
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiProblemException(TwoFactorProblem.REAUTHENTICATION_FAILED);
        }
    }

    /**
     * Scrubs and shreds the account. Already deleted: nothing to do.
     *
     * @return the stored profile photo to remove once this commits (a file
     *         removed before a rollback could not be put back); empty when
     *         there is none or nothing was deleted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> delete(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            return Optional.empty();
        }
        String photo = user.getProfilePhotoKey();
        Instant now = clock.instant();
        sessions.revokeEverySession(userId, SessionRevocationReason.ADMIN);
        jdbc.update("""
                UPDATE users SET email = 'deleted-' || id || '@deleted.invalid', full_name = 'Deleted user',
                       phone_ciphertext = NULL, phone_blind_index = NULL, phone_verified_at = NULL,
                       profile_photo_key = NULL, id_verification_tier = 'NONE', account_status = 'DELETED',
                       deleted_at = :now, updated_at = :now
                WHERE id = :id""", new MapSqlParameterSource("id", userId).addValue("now", Timestamp.from(now)));
        jdbc.update("DELETE FROM emergency_contacts WHERE user_id = :id", new MapSqlParameterSource("id", userId));
        jdbc.update("DELETE FROM upi_ids WHERE user_id = :id", new MapSqlParameterSource("id", userId));
        jdbc.update("UPDATE vouches SET revoked_at = :now WHERE (voucher_user_id = :id OR vouched_user_id = :id) "
                + "AND revoked_at IS NULL", new MapSqlParameterSource("id", userId).addValue("now", Timestamp.from(now)));
        encryption.shred(userId);
        return Optional.ofNullable(photo);
    }

    /** Removes a file {@link #delete} handed back, after its transaction committed. */
    public void removeStoredFile(String key) {
        storage.delete(key);
    }
}
