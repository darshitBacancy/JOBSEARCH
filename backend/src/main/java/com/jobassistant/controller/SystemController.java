package com.jobassistant.controller;

import com.jobassistant.ai.AiService;
import com.jobassistant.config.OpenRouterProperties;
import com.jobassistant.config.RagProperties;
import com.jobassistant.dto.IndexStatusDto;
import com.jobassistant.dto.SystemStatusDto;
import com.jobassistant.rag.IndexingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public status for the UI. Reports whether a key is configured, never the key itself. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AiService aiService;
    private final OpenRouterProperties openRouter;
    private final RagProperties rag;
    private final IndexingService indexingService;

    public SystemController(AiService aiService, OpenRouterProperties openRouter, RagProperties rag,
                            IndexingService indexingService) {
        this.aiService = aiService;
        this.openRouter = openRouter;
        this.rag = rag;
        this.indexingService = indexingService;
    }

    @GetMapping("/status")
    public SystemStatusDto status() {
        IndexStatusDto index = indexingService.status();
        AiService.Health health = aiService.health();
        return new SystemStatusDto(aiService.isConfigured(),
                health == null ? "UNKNOWN" : health.status(), health == null ? null : health.lastError(),
                aiService.modelName(),
                index.embeddingProvider() != null ? index.embeddingProvider() : rag.embeddingProvider(),
                index.embeddingModel() != null ? index.embeddingModel() : openRouter.embeddingModel(),
                "in-memory (snapshot: " + rag.vectorStoreFile() + ")", rag.debugEnabled(), index);
    }
}
