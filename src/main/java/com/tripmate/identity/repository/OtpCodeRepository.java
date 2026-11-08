package com.tripmate.identity.repository;

import com.tripmate.identity.entity.OtpChannel;
import com.tripmate.identity.entity.OtpCode;
import com.tripmate.identity.entity.OtpPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    /**
     * Row-locked so concurrent guesses against one code are counted one at a
     * time; without the lock, N parallel requests could all read
     * attempt_count = 0 and get N guesses for the price of one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OtpCode o where o.id = :id")
    Optional<OtpCode> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Owner of a code, read as a bare column rather than an entity. Callers
     * use it to lock the user row first; loading the OtpCode entity here
     * would put an unlocked copy in the persistence context, and a later
     * locking query would hand back that stale copy instead of re-reading it.
     */
    @Query("""
            select o.userId from OtpCode o
            where o.id = :id and o.channel = :channel and o.purpose = :purpose""")
    Optional<UUID> findOwnerId(@Param("id") UUID id, @Param("channel") OtpChannel channel,
                               @Param("purpose") OtpPurpose purpose);

    /**
     * The newest code for a user and purpose. Issuing a code supersedes the
     * previous one, so this is the only code that can still be live.
     */
    Optional<OtpCode> findFirstByUserIdAndChannelAndPurposeOrderByCreatedAtDesc(
            UUID userId, OtpChannel channel, OtpPurpose purpose);

    @Query("""
            select count(o) from OtpCode o
            where o.userId = :userId and o.channel = :channel and o.purpose = :purpose
              and o.createdAt > :since""")
    long countIssuedSince(@Param("userId") UUID userId, @Param("channel") OtpChannel channel,
                          @Param("purpose") OtpPurpose purpose, @Param("since") Instant since);
}
