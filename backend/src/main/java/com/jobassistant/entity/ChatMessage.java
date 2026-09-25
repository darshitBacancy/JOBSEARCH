package com.jobassistant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_msg_conversation", columnList = "conversationId,timestamp"))
public class ChatMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String message;

    /** Jobs attached to an assistant message (for history rendering and follow-ups). */
    @Convert(converter = StringListConverter.class)
    @Column(length = 1000)
    private List<String> jobIds = new ArrayList<>();

    @Column(nullable = false)
    private Instant timestamp;

    /** User rating of an assistant reply: "up", "down" or null. */
    @Column(length = 8)
    private String feedback;

    public ChatMessage() {
    }

    public ChatMessage(String conversationId, Role role, String message, List<String> jobIds) {
        this.conversationId = conversationId;
        this.role = role;
        this.message = message;
        this.jobIds = jobIds == null ? new ArrayList<>() : new ArrayList<>(jobIds);
        this.timestamp = Instant.now();
    }

    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public Role getRole() { return role; }
    public String getMessage() { return message; }
    public List<String> getJobIds() { return jobIds; }
    public Instant getTimestamp() { return timestamp; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
}
