package com.tripmate.identity.repository;

import com.tripmate.identity.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Case-insensitive because the column is citext. The JDBC URL sends
     * strings untyped (stringtype=unspecified), so PostgreSQL resolves this
     * parameter as citext; sent as varchar, the comparison would silently fall
     * back to case-sensitive text equality.
     *
     * <p>Row-locked so two registrations for the same pending address are
     * applied one after the other, never interleaved.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email and u.deletedAt is null")
    Optional<User> findLiveByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Whether another live account has already confirmed this number. Served
     * by idx_users_phone_verified_unique, which also makes the answer final:
     * two accounts confirming the same number at once cannot both commit.
     */
    @Query("""
            select count(u) > 0 from User u
            where u.phoneBlindIndex = :blindIndex and u.phoneVerifiedAt is not null
              and u.deletedAt is null and u.id <> :exceptUserId""")
    boolean isPhoneVerifiedByAnother(@Param("blindIndex") String blindIndex, @Param("exceptUserId") UUID exceptUserId);

    /**
     * The trust module's cache column, written without loading the entity:
     * the score is the trust module's, and the rest of the row is not
     * touched.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE users SET trust_score = :score, trust_score_updated_at = :at WHERE id = :id",
            nativeQuery = true)
    void recordTrustScore(@Param("id") UUID id, @Param("score") BigDecimal score, @Param("at") Instant at);

    /** Unlocked read for login, which changes nothing on the user row. Case-insensitive like the locking variant. */
    @Query("select u from User u where u.email = :email and u.deletedAt is null")
    Optional<User> findLiveByEmail(@Param("email") String email);
}
