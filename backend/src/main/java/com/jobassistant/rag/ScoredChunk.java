package com.jobassistant.rag;

/** A retrieved chunk and its similarity to the query (cosine for vector search). */
public record ScoredChunk(VectorRecord record, double score) {
}
