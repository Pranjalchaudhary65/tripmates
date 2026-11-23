package com.tripmate.identity.repository;

import com.tripmate.identity.entity.IdentityVerification;
import com.tripmate.identity.entity.VerificationStatus;
import com.tripmate.identity.entity.VerificationTier;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** No delete: a verification's outcome is kept after its image is purged. */
public interface IdentityVerificationRepository extends Repository<IdentityVerification, UUID> {

    IdentityVerification save(IdentityVerification verification);

    Optional<IdentityVerification> findById(UUID id);

    /** Row-locked, so two reviewers deciding the same selfie are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from IdentityVerification v where v.id = :id")
    Optional<IdentityVerification> findByIdForUpdate(@Param("id") UUID id);

    /** The newest attempt at a tier: what the verification centre shows. Served by idx_idverif_user_tier_status. */
    Optional<IdentityVerification> findFirstByUserIdAndTierOrderByCreatedAtDesc(UUID userId, VerificationTier tier);

    /** At most one row, by idx_idverif_one_pending_selfie. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from IdentityVerification v
            where v.userId = :userId and v.tier = com.tripmate.identity.entity.VerificationTier.SELFIE_MATCH
              and v.status = com.tripmate.identity.entity.VerificationStatus.PENDING""")
    Optional<IdentityVerification> findPendingSelfieForUpdate(@Param("userId") UUID userId);

    /** The admin queue, oldest first. Served by idx_idverif_pending_queue. */
    Page<IdentityVerification> findByTierAndStatusOrderByCreatedAtAsc(VerificationTier tier, VerificationStatus status,
                                                                      Pageable pageable);

    /**
     * Rows whose image is past its retention deadline. SKIP LOCKED, so two
     * app instances running the purge at once split the rows between them
     * instead of queueing on the same ones. Served by idx_idverif_purge_due.
     */
    @Query(value = """
            select * from identity_verifications
            where selfie_image_key is not null and purge_due_at <= :now
            order by purge_due_at
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<IdentityVerification> lockDueForPurge(@Param("now") Instant now, @Param("limit") int limit);
}
