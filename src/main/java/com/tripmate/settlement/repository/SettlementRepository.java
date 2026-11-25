package com.tripmate.settlement.repository;

import com.tripmate.settlement.entity.Settlement;
import com.tripmate.settlement.entity.SettlementStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Settlements are changed only through their state-machine methods; there is no delete. */
public interface SettlementRepository extends Repository<Settlement, UUID> {

    Settlement save(Settlement settlement);

    Optional<Settlement> findById(UUID id);

    /** Which trip a settlement belongs to, read before that trip's ledger is locked. */
    @Query("select s.tripId from Settlement s where s.id = :id")
    Optional<UUID> findTripIdById(@Param("id") UUID id);

    /** Served by idx_settlements_trip. */
    @Query("select s from Settlement s where s.tripId = :trip and s.status in :statuses order by s.createdAt, s.id")
    List<Settlement> findByTripAndStatuses(@Param("trip") UUID tripId,
                                           @Param("statuses") Collection<SettlementStatus> statuses);
}
