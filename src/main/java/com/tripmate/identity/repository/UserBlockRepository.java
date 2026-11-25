package com.tripmate.identity.repository;

import com.tripmate.identity.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    Optional<UserBlock> findByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    List<UserBlock> findTop200ByBlockerIdOrderByCreatedAtDesc(UUID blockerId);

    /** Either direction: a block hides two people from each other whoever made it. */
    @Query("""
            select count(b) > 0 from UserBlock b
            where (b.blockerId = :one and b.blockedId = :other) or (b.blockerId = :other and b.blockedId = :one)""")
    boolean existsBetween(@Param("one") UUID one, @Param("other") UUID other);
}
