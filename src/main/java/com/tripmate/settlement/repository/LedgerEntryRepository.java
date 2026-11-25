package com.tripmate.settlement.repository;

import com.tripmate.settlement.entity.LedgerEntry;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Append-only: insert and read, nothing else (DESIGN.md, Append-only enforcement). */
public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {

    LedgerEntry save(LedgerEntry entry);

    /** Served by idx_ledger_transaction. */
    @Query("select e from LedgerEntry e where e.transactionId in :ids order by e.transactionId, e.entryType, "
            + "e.accountUserId")
    List<LedgerEntry> findByTransactions(@Param("ids") Collection<UUID> transactionIds);

    /**
     * Every account on the trip with the total of each kind of leg, one row
     * per account and leg type: {accountUserId, entryType, sum}. Served by
     * idx_ledger_entries_trip_account. Read in the same query language as
     * the writes, so legs written earlier in the transaction are flushed
     * before it runs.
     */
    @Query("select e.accountUserId, e.entryType, sum(e.amountPaise) from LedgerEntry e where e.tripId = :trip "
            + "group by e.accountUserId, e.entryType")
    List<Object[]> totalsByAccount(@Param("trip") UUID tripId);
}
