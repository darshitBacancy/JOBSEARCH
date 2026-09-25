package com.jobassistant.controller;

import com.jobassistant.conversation.ConversationService;
import com.jobassistant.dto.ChatMessageDto;
import com.jobassistant.dto.ChatRequest;
import com.jobassistant.dto.ChatResponse;
import com.jobassistant.dto.ConversationSummaryDto;
import com.jobassistant.dto.RegenerateRequest;
import com.jobassistant.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.handle(request);
    }

    /** Re-asks the last user message of a conversation, replacing the last reply. */
    @PostMapping("/chat/regenerate")
    public ResponseEntity<ChatResponse> regenerate(@Valid @RequestBody RegenerateRequest request) {
        if (!conversationService.exists(request.conversationId())) return ResponseEntity.notFound().build();
        String message = conversationService.popLastUserTurn(request.conversationId())
                .orElseThrow(() -> new IllegalArgumentException("conversation has no user message to regenerate"));
        return ResponseEntity.ok(chatService.handle(
                new ChatRequest(request.conversationId(), message, null, null, null, request.debug())));
    }

    @GetMapping("/conversations")
    public List<ConversationSummaryDto> conversations() {
        return conversationService.list();
    }

    @PutMapping("/conversations/{id}")
    public ResponseEntity<Void> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) return ResponseEntity.badRequest().build();
        return conversationService.rename(id, title) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/conversations")
    public ResponseEntity<Void> deleteAll() {
        conversationService.deleteAll();
        return ResponseEntity.noContent().build();
    }

    /** Deletes {@code fromMessageId} and every later message (edit-and-resend). */
    @PostMapping("/conversations/{id}/truncate")
    public ResponseEntity<Void> truncate(@PathVariable String id, @RequestBody Map<String, Long> body) {
        if (!conversationService.exists(id)) return ResponseEntity.notFound().build();
        Long from = body == null ? null : body.get("fromMessageId");
        return conversationService.truncate(id, from) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return conversationService.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<ChatMessageDto>> messages(@PathVariable String id) {
        if (!conversationService.exists(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(conversationService.history(id));
    }
}
