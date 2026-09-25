package com.jobassistant.dto;

public record IndexStatusDto(
        boolean indexed,
        int jobCount,
        int chunkCount,
        String embeddingProvider,
        String embeddingModel,
        int dimensions,
        String indexedAt,
        Long durationMs,
        String message) {
}
