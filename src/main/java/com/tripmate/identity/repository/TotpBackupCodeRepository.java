package com.tripmate.identity.repository;

import com.tripmate.identity.entity.TotpBackupCode;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Callers hold the owner's user row lock, which is what serialises "use a code" against itself. */
public interface TotpBackupCodeRepository extends Repository<TotpBackupCode, UUID> {

    TotpBackupCode save(TotpBackupCode code);

    /** Served by idx_backup_codes_user_unused. */
    List<TotpBackupCode> findByUserIdAndUsedAtIsNull(UUID userId);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    /**
     * Regenerating or turning 2FA off: the old set must stop working in the
     * same transaction. Flushes first, so a code marked used earlier in the
     * transaction is written before it is deleted. Does not clear the
     * persistence context: the caller's locked User must stay managed.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from TotpBackupCode c where c.userId = :userId")
    int deleteAllOf(@Param("userId") UUID userId);
}
