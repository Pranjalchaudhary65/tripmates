package com.tripmate.identity.repository;

import com.tripmate.identity.entity.UpiId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UpiIdRepository extends JpaRepository<UpiId, UUID> {
}
