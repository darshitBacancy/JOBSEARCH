package com.jobassistant.dto;

/** Public system status. Deliberately contains no secrets - only whether a key is configured. */
public record SystemStatusDto(
        boolean aiConfigured,
        String aiStatus,
        String aiLastError,
        String chatModel,
        String embeddingProvider,
        String embeddingModel,
        String vectorStore,
        boolean debugEnabled,
        IndexStatusDto index) {
}
