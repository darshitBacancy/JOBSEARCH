package com.jobassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the RAG pipeline: data source, chunking, retrieval, ranking weights and debugging.
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String jobsData,
        String embeddingProvider,
        String vectorStoreFile,
        Boolean reindexOnStartup,
        Integer chunkMaxChars,
        Integer embeddingBatchSize,
        Integer retrievalTopK,
        Integer maxJobsReturned,
        Integer candidatePoolSize,
        Integer historyWindow,
        Boolean debugEnabled,
        Ranking ranking) {

    public RagProperties {
        if (jobsData == null || jobsData.isBlank()) jobsData = "classpath:data/jobs.json";
        if (embeddingProvider == null || embeddingProvider.isBlank()) embeddingProvider = "auto";
        if (vectorStoreFile == null) vectorStoreFile = "./db/vector-store.bin";
        if (reindexOnStartup == null) reindexOnStartup = false;
        if (chunkMaxChars == null || chunkMaxChars < 200) chunkMaxChars = 900;
        if (embeddingBatchSize == null || embeddingBatchSize <= 0) embeddingBatchSize = 32;
        if (retrievalTopK == null || retrievalTopK <= 0) retrievalTopK = 20;
        if (maxJobsReturned == null || maxJobsReturned <= 0) maxJobsReturned = 5;
        if (candidatePoolSize == null || candidatePoolSize <= 0) candidatePoolSize = 50;
        if (historyWindow == null || historyWindow < 0) historyWindow = 8;
        if (debugEnabled == null) debugEnabled = true;
        if (ranking == null) ranking = new Ranking(null, null, null, null, null, null);
    }

    /** Relevance weights. They are re-normalised over the components that apply to a query. */
    public record Ranking(Double semantic, Double skills, Double experience,
                          Double location, Double remote, Double salary) {
        public Ranking {
            if (semantic == null) semantic = 0.35;
            if (skills == null) skills = 0.25;
            if (experience == null) experience = 0.15;
            if (location == null) location = 0.10;
            if (remote == null) remote = 0.05;
            if (salary == null) salary = 0.10;
        }
    }
}
