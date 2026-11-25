package com.tripmate.settlement.repository;

import com.tripmate.settlement.entity.LedgerKind;
import com.tripmate.settlement.entity.LedgerTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only: insert and read, nothing else (DESIGN.md, Append-only enforcement). */
public interface LedgerTransactionRepository extends Repository<LedgerTransaction, UUID> {

    LedgerTransaction save(LedgerTransaction transaction);

    Optional<LedgerTransaction> findById(UUID id);

    Optional<LedgerTransaction> findByRecordedByUserIdAndIdempotencyKey(UUID recordedByUserId, String idempotencyKey);

    boolean existsByReversesId(UUID reversesId);

    /** Served by idx_ledger_transactions_trip_time. */
    @Query("select t from LedgerTransaction t where t.tripId = :trip order by t.createdAt desc, t.id")
    Page<LedgerTransaction> findByTrip(@Param("trip") UUID tripId, Pageable pageable);

    @Query("select t from LedgerTransaction t where t.tripId = :trip and t.kind = :kind "
            + "order by t.createdAt desc, t.id")
    List<LedgerTransaction> findByTripAndKind(@Param("trip") UUID tripId, @Param("kind") LedgerKind kind,
                                              Pageable limit);

    /** Which of these expenses have been reversed. */
    @Query("select t.reversesId from LedgerTransaction t where t.reversesId in :ids")
    List<UUID> findReversed(@Param("ids") Collection<UUID> expenseIds);

    @Query("select coalesce(sum(t.amountPaise), 0) from LedgerTransaction t where t.tripId = :trip and t.kind = :kind")
    long sumByTripAndKind(@Param("trip") UUID tripId, @Param("kind") LedgerKind kind);
}
