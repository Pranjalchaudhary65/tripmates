package com.tripmate.identity.repository;

import com.tripmate.identity.entity.Vouch;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Callers creating a vouch hold the voucher's user row lock, which is what
 * makes "count, then insert" safe against the cap.
 */
public interface VouchRepository extends Repository<Vouch, UUID> {

    Vouch save(Vouch vouch);

    Optional<Vouch> findById(UUID id);

    Optional<Vouch> findByVoucherUserIdAndVouchedUserId(UUID voucherUserId, UUID vouchedUserId);

    /** Served by idx_vouches_voucher_active. */
    @Query("select count(v) from Vouch v where v.voucherUserId = :voucher and v.revokedAt is null")
    long countActiveGivenBy(@Param("voucher") UUID voucherUserId);

    @Query("""
            select v from Vouch v
            where v.voucherUserId = :voucher and v.revokedAt is null
            order by v.createdAt desc""")
    List<Vouch> findActiveGivenBy(@Param("voucher") UUID voucherUserId);

    /** Served by idx_vouches_vouched_active. */
    @Query("select count(v) from Vouch v where v.vouchedUserId = :vouched and v.revokedAt is null")
    long countActiveReceivedBy(@Param("vouched") UUID vouchedUserId);

    /** Served by idx_vouches_vouched_active. */
    @Query("select v.voucherUserId from Vouch v where v.vouchedUserId = :vouched and v.revokedAt is null")
    List<UUID> findActiveVouchersOf(@Param("vouched") UUID vouchedUserId);
}
