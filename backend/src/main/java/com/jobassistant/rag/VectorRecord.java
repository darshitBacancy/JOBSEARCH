package com.jobassistant.rag;

import java.util.Map;

/**
 * One chunk in the vector store: the chunk text, its embedding, and metadata linking it
 * back to the job it came from.
 *
 * @param documentId unique chunk id, e.g. {@code job-101-requirements-0}
 * @param jobId      id of the source job
 * @param section    OVERVIEW, SKILLS, REQUIREMENTS, RESPONSIBILITIES or BENEFITS
 * @param chunkText  text that was embedded
 * @param embedding  L2-normalised vector
 * @param metadata   jobId, title, company, location, skills, remote, section
 */
public record VectorRecord(String documentId, long jobId, String section, int chunkIndex,
                           String chunkText, float[] embedding, Map<String, String> metadata) {
}
