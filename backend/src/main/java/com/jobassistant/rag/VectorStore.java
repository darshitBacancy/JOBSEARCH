package com.jobassistant.rag;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Vector database abstraction. The prototype uses {@link InMemoryVectorStore}; a pgvector or
 * Qdrant implementation could be dropped in behind this interface.
 */
public interface VectorStore {

    /** Atomically replace the whole index. */
    void replaceAll(Collection<VectorRecord> records, IndexInfo info);

    /**
     * Cosine-similarity search.
     *
     * @param allowedJobIds restrict results to these jobs (structured pre-filter); null = no restriction
     */
    List<ScoredChunk> similaritySearch(float[] queryEmbedding, int topK, Set<Long> allowedJobIds);

    /** Lexical fallback used when the query cannot be embedded (e.g. provider outage). */
    List<ScoredChunk> keywordSearch(String query, int topK, Set<Long> allowedJobIds);

    List<VectorRecord> findByJobId(long jobId);

    int size();

    Optional<IndexInfo> info();
}
