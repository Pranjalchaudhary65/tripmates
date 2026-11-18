package com.tripmate.identity.repository;

import com.tripmate.identity.entity.RefreshTokenFamily;
import com.tripmate.identity.entity.SessionRevocationReason;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * No delete method, and no way to save a changed entity: a family is
 * inserted once, then only changed by the two updates below. Both match
 * live rows only, so whichever of a refresh and a revocation commits second
 * cannot undo the first: a refresh never brings a revoked family back, and a
 * revocation is never overwritten by a late refresh.
 */
public interface RefreshTokenFamilyRepository extends Repository<RefreshTokenFamily, UUID> {

    RefreshTokenFamily save(RefreshTokenFamily family);

    Optional<RefreshTokenFamily> findById(UUID id);

    /**
     * The sessions list. Bounded because nothing else bounds how many times
     * an account can sign in within a family's lifetime; the newest are the
     * ones a user is looking for.
     */
    List<RefreshTokenFamily> findTop100ByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(
            UUID userId, Instant now);

    /** Every live family of a user, expired or not, so revoking all leaves none behind. Uses idx_rtf_user_active. */
    @Query("select f.id from RefreshTokenFamily f where f.userId = :userId and f.revokedAt is null")
    List<UUID> findLiveIdsByUserId(@Param("userId") UUID userId);

    /** @return 0 when the family was revoked in the meantime, 1 otherwise */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenFamily f
               set f.lastUsedAt = :now, f.lastIpAddress = :ip, f.updatedAt = :now
             where f.id = :id and f.revokedAt is null""")
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("ip") InetAddress ip);

    /**
     * Scoped by user as well as by ID, so an ID that belongs to someone else
     * changes nothing even if a caller passes one.
     *
     * @return how many families were live and are now revoked
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenFamily f
               set f.revokedAt = :now, f.revokedReason = :reason, f.updatedAt = :now
             where f.userId = :userId and f.id in :ids and f.revokedAt is null""")
    int revoke(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids,
               @Param("reason") SessionRevocationReason reason, @Param("now") Instant now);
}
