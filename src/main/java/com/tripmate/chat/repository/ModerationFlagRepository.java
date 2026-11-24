package com.tripmate.chat.repository;

import com.tripmate.chat.entity.ModerationFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModerationFlagRepository extends JpaRepository<ModerationFlag, UUID> {

    List<ModerationFlag> findByChatMessageId(UUID chatMessageId);
}
