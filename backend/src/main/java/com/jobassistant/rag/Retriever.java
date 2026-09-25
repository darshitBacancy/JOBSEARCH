package com.jobassistant.rag;

import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.ai.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Query side of RAG: embed the query with the same model that built the index, then run a
 * similarity search in the vector store (optionally pre-filtered to a set of job ids).
 * Falls back to lexical search over the same chunks if the query cannot be embedded.
 */
@Component
public class Retriever {

    private static final Logger log = LoggerFactory.getLogger(Retriever.class);

    public record Result(List<ScoredChunk> chunks, String mode, String embeddingProvider, String embeddingModel,
                         String note) {
        public static Result none(String note) {
            return new Result(List.of(), "none", null, null, note);
        }
    }

    private final VectorStore vectorStore;
    private final EmbeddingProviderSelector selector;

    public Retriever(VectorStore vectorStore, EmbeddingProviderSelector selector) {
        this.vectorStore = vectorStore;
        this.selector = selector;
    }

    public Result retrieve(String query, int topK, Set<Long> allowedJobIds) {
        if (query == null || query.isBlank()) return Result.none("Empty query");
        if (allowedJobIds != null && allowedJobIds.isEmpty()) return Result.none("No candidate jobs to search");
        Optional<IndexInfo> info = vectorStore.info();
        if (info.isEmpty() || vectorStore.size() == 0) {
            return Result.none("Vector index is not ready yet");
        }
        String provider = info.get().embeddingProvider();
        Optional<EmbeddingService> embedder = selector.byProvider(provider);
        if (embedder.isPresent() && embedder.get().isAvailable()) {
            try {
                float[] q = embedder.get().embed(query);
                List<ScoredChunk> chunks = vectorStore.similaritySearch(q, topK, allowedJobIds);
                return new Result(chunks, "vector", provider, info.get().embeddingModel(), null);
            } catch (AiUnavailableException e) {
                log.warn("Query embedding failed, using keyword retrieval: {}", e.getMessage());
                return new Result(vectorStore.keywordSearch(query, topK, allowedJobIds), "keyword", provider,
                        info.get().embeddingModel(), "Query embedding failed (" + e.getMessage() + "); used keyword retrieval");
            }
        }
        return new Result(vectorStore.keywordSearch(query, topK, allowedJobIds), "keyword", provider,
                info.get().embeddingModel(), "Embedding provider '" + provider + "' unavailable; used keyword retrieval");
    }
}
