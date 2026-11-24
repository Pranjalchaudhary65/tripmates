package com.tripmate.chat.repository;

import com.tripmate.chat.entity.ChatMessage;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Messages are inserted and read; nothing here changes or removes one. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Optional<ChatMessage> findByConversationIdAndSenderIdAndClientMessageId(UUID conversationId, UUID senderId,
                                                                           UUID clientMessageId);

    /** Newest first, below {@code beforeSeq}; served by uq_chat_messages_conversation_seq. */
    @Query("""
            select m from ChatMessage m
            where m.conversationId = :conversationId and m.seq < :beforeSeq
            order by m.seq desc""")
    List<ChatMessage> findPageBefore(@Param("conversationId") UUID conversationId,
                                     @Param("beforeSeq") long beforeSeq, Limit limit);

    /** The other person's messages above the reader's marker. */
    @Query("""
            select count(m) from ChatMessage m
            where m.conversationId = :conversationId and m.kind = com.tripmate.chat.entity.MessageKind.USER
              and m.senderId <> :readerId and m.seq > :afterSeq""")
    long countUnread(@Param("conversationId") UUID conversationId, @Param("readerId") UUID readerId,
                     @Param("afterSeq") long afterSeq);
}
