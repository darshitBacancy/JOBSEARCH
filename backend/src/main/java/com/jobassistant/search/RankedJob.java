package com.jobassistant.search;

import com.jobassistant.entity.Job;
import com.jobassistant.rag.ScoredChunk;

import java.util.List;
import java.util.Map;

/**
 * A job with its relevance score, the per-component breakdown (null = not applicable to the
 * query), human-readable reasons and gaps, and the chunks that were retrieved for it.
 */
public record RankedJob(Job job, double score, Map<String, Double> breakdown, List<String> reasons,
                        List<String> gaps, double semanticSimilarity, List<ScoredChunk> chunks) {

    public int matchPercent() {
        return (int) Math.round(score * 100);
    }

    public String matchLabel() {
        if (score >= 0.75) return "Strong match";
        if (score >= 0.5) return "Good match";
        return "Partial match";
    }
}
