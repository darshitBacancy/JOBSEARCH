package com.jobassistant.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.AiMessage;
import com.jobassistant.dto.ChatMessageDto;
import com.jobassistant.dto.ConversationSummaryDto;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.ChatMessage;
import com.jobassistant.entity.Conversation;
import com.jobassistant.repository.ChatMessageRepository;
import com.jobassistant.repository.ConversationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversation memory: persists messages and the search state that follow-up questions refer
 * to, and provides a bounded history window for the LLM.
 */
@Service
public class ConversationService {

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};

    /** Snapshot of what the conversation currently "points at". */
    public record ConversationState(JobSearchCriteria lastCriteria, String lastSemanticQuery,
                                    List<Long> candidateJobIds, List<Long> shownJobIds, Long focusJobId) {
        public boolean hasResults() {
            return !shownJobIds.isEmpty();
        }
    }

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ObjectMapper mapper;

    public ConversationService(ConversationRepository conversations, ChatMessageRepository messages, ObjectMapper mapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.mapper = mapper;
    }

    @Transactional
    public Conversation getOrCreate(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            var existing = conversations.findById(conversationId);
            if (existing.isPresent()) return existing.get();
        }
        Conversation c = new Conversation();
        c.setId(conversationId != null && conversationId.matches("[A-Za-z0-9-]{8,36}") ? conversationId
                : UUID.randomUUID().toString());
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return conversations.save(c);
    }

    @Transactional
    public ChatMessage addMessage(String conversationId, ChatMessage.Role role, String text, List<Long> jobIds) {
        List<String> ids = jobIds == null ? List.of() : jobIds.stream().map(String::valueOf).toList();
        ChatMessage saved = messages.save(new ChatMessage(conversationId, role, text, ids));
        conversations.findById(conversationId).ifPresent(c -> {
            if (role == ChatMessage.Role.USER && (c.getTitle() == null || c.getTitle().isBlank())) {
                c.setTitle(titleFrom(text));
            }
            c.setUpdatedAt(Instant.now());
            conversations.save(c);
        });
        return saved;
    }

    /** The last {@code window} messages before the current one, oldest first, as LLM messages. */
    public List<AiMessage> recentHistory(String conversationId, int window, Long excludeMessageId) {
        if (window <= 0) return List.of();
        List<ChatMessage> recent = new ArrayList<>(messages.findByConversationIdOrderByTimestampDescIdDesc(
                conversationId, PageRequest.of(0, window + 1)));
        recent.removeIf(m -> m.getId().equals(excludeMessageId));
        if (recent.size() > window) recent = recent.subList(0, window);
        Collections.reverse(recent);
        return recent.stream().map(m -> m.getRole() == ChatMessage.Role.USER
                ? AiMessage.user(m.getMessage()) : AiMessage.assistant(m.getMessage())).toList();
    }

    public ConversationState state(Conversation c) {
        return new ConversationState(readCriteria(c.getLastCriteriaJson()), c.getLastSemanticQuery(),
                readIds(c.getLastCandidateJobIds()), readIds(c.getLastShownJobIds()), c.getFocusJobId());
    }

    @Transactional
    public void saveSearchState(Conversation c, JobSearchCriteria criteria, String semanticQuery,
                                List<Long> candidateIds, List<Long> shownIds) {
        c.setLastCriteriaJson(write(criteria));
        c.setLastSemanticQuery(semanticQuery == null ? null
                : semanticQuery.length() > 3900 ? semanticQuery.substring(0, 3900) : semanticQuery);
        c.setLastCandidateJobIds(write(candidateIds));
        c.setLastShownJobIds(write(shownIds));
        c.setFocusJobId(null);
        c.setUpdatedAt(Instant.now());
        conversations.save(c);
    }

    @Transactional
    public void saveFocus(Conversation c, Long focusJobId) {
        c.setFocusJobId(focusJobId);
        c.setUpdatedAt(Instant.now());
        conversations.save(c);
    }

    public List<ChatMessageDto> history(String conversationId) {
        return messages.findByConversationIdOrderByTimestampAscIdAsc(conversationId).stream()
                .map(m -> new ChatMessageDto(m.getId(), m.getRole().name(), m.getMessage(),
                        m.getJobIds().stream().map(Long::valueOf).toList(), m.getTimestamp().toString(),
                        m.getFeedback()))
                .toList();
    }

    /** Conversations that have at least one message, most recently active first. */
    public List<ConversationSummaryDto> list() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : messages.countPerConversation()) counts.put((String) row[0], (Long) row[1]);
        List<ConversationSummaryDto> out = new ArrayList<>();
        for (Conversation c : conversations.findAllByOrderByUpdatedAtDesc()) {
            long count = counts.getOrDefault(c.getId(), 0L);
            if (count == 0) continue;
            String title = c.getTitle();
            if (title == null || title.isBlank()) {
                // conversations created before titles existed
                ChatMessage first = messages.findFirstByConversationIdAndRoleOrderByIdAsc(c.getId(), ChatMessage.Role.USER);
                title = first != null ? titleFrom(first.getMessage()) : "New chat";
            }
            out.add(new ConversationSummaryDto(c.getId(), title, String.valueOf(c.getCreatedAt()),
                    String.valueOf(c.getUpdatedAt()), count));
        }
        return out;
    }

    @Transactional
    public boolean rename(String conversationId, String title) {
        return conversations.findById(conversationId).map(c -> {
            c.setTitle(titleFrom(title));
            conversations.save(c);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean delete(String conversationId) {
        if (!conversations.existsById(conversationId)) return false;
        messages.deleteByConversationId(conversationId);
        conversations.deleteById(conversationId);
        return true;
    }

    /** Deletes {@code fromMessageId} and every later message; false when it isn't in this conversation. */
    @Transactional
    public boolean truncate(String conversationId, Long fromMessageId) {
        if (fromMessageId == null) return false;
        var msg = messages.findById(fromMessageId);
        if (msg.isEmpty() || !msg.get().getConversationId().equals(conversationId)) return false;
        messages.deleteFrom(conversationId, fromMessageId);
        touch(conversationId);
        return true;
    }

    /**
     * Removes the last exchange (the trailing assistant reply, if any, and the user message before it)
     * and returns that user message so it can be asked again. Empty when there is no user message.
     */
    @Transactional
    public Optional<String> popLastUserTurn(String conversationId) {
        List<ChatMessage> all = messages.findByConversationIdOrderByTimestampAscIdAsc(conversationId);
        for (int i = all.size() - 1; i >= 0; i--) {
            ChatMessage m = all.get(i);
            if (m.getRole() == ChatMessage.Role.USER) {
                messages.deleteFrom(conversationId, m.getId());
                return Optional.of(m.getMessage());
            }
        }
        return Optional.empty();
    }

    /** @return false when the message does not exist */
    @Transactional
    public boolean setFeedback(Long messageId, String rating) {
        return messages.findById(messageId).map(m -> {
            m.setFeedback(rating);
            messages.save(m);
            return true;
        }).orElse(false);
    }

    @Transactional
    public void deleteAll() {
        messages.deleteAllInBatch();
        conversations.deleteAllInBatch();
    }

    private void touch(String conversationId) {
        conversations.findById(conversationId).ifPresent(c -> {
            c.setUpdatedAt(Instant.now());
            conversations.save(c);
        });
    }

    static String titleFrom(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) return "New chat";
        return t.length() > 60 ? t.substring(0, 57).strip() + "..." : t;
    }

    public boolean exists(String conversationId) {
        return conversations.existsById(conversationId);
    }

    private JobSearchCriteria readCriteria(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, JobSearchCriteria.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> readIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, LONG_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
