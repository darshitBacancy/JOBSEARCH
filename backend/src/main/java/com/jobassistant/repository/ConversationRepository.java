package com.jobassistant.repository;

import com.jobassistant.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findAllByOrderByUpdatedAtDesc();
}
