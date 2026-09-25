package com.jobassistant.repository;

import com.jobassistant.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByTimestampAscIdAsc(String conversationId);

    /** Newest first; callers reverse it. Used to bound the history sent to the LLM. */
    List<ChatMessage> findByConversationIdOrderByTimestampDescIdDesc(String conversationId, Pageable pageable);

    /** [conversationId, messageCount] for every conversation that has messages. */
    @Query("select m.conversationId, count(m) from ChatMessage m group by m.conversationId")
    List<Object[]> countPerConversation();

    ChatMessage findFirstByConversationIdAndRoleOrderByIdAsc(String conversationId, ChatMessage.Role role);

    @Modifying
    @Query("delete from ChatMessage m where m.conversationId = :conversationId")
    void deleteByConversationId(String conversationId);

    /** Removes a message and everything after it (ids are monotonic within the table). */
    @Modifying
    @Query("delete from ChatMessage m where m.conversationId = :conversationId and m.id >= :fromId")
    int deleteFrom(String conversationId, Long fromId);
}
