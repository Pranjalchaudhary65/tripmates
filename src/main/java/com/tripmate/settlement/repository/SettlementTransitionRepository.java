package com.tripmate.settlement.repository;

import com.tripmate.settlement.entity.SettlementTransition;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/** Append-only: insert and read, nothing else (DESIGN.md, Append-only enforcement). */
public interface SettlementTransitionRepository extends Repository<SettlementTransition, UUID> {

    SettlementTransition save(SettlementTransition transition);

    List<SettlementTransition> findBySettlementIdOrderByCreatedAt(UUID settlementId);
}
