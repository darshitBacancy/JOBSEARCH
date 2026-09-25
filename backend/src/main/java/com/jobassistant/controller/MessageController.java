package com.jobassistant.controller;

import com.jobassistant.conversation.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private static final Set<String> RATINGS = Set.of("up", "down");

    private final ConversationService conversationService;

    public MessageController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** Thumbs up/down on a reply; {@code {"rating": null}} clears it. */
    @PutMapping("/{id}/feedback")
    public ResponseEntity<Void> feedback(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object rating = body == null ? null : body.get("rating");
        if (rating != null && !(rating instanceof String s && RATINGS.contains(s))) {
            throw new IllegalArgumentException("rating must be \"up\", \"down\" or null");
        }
        return conversationService.setFeedback(id, (String) rating)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
