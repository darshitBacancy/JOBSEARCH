package com.jobassistant.rag;

import java.time.Instant;

/** Describes how the current vector index was built, so we know when it must be rebuilt. */
public record IndexInfo(String embeddingProvider, String embeddingModel, int dimensions,
                        String datasetHash, int jobCount, int chunkCount, Instant indexedAt) {
}
