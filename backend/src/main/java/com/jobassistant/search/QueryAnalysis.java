package com.jobassistant.search;

import com.jobassistant.conversation.ChatIntent;
import com.jobassistant.dto.JobSearchCriteria;

import java.util.List;

/**
 * Result of understanding one user message.
 *
 * @param standaloneQuery the message rewritten to be self-contained (used for semantic retrieval)
 * @param ordinals        1-based positions in the previous result list ("the first three" -> 1,2,3)
 * @param jobIds          explicit job ids mentioned ("#118")
 * @param source          "llm", "rules" or "llm+rules"
 */
public record QueryAnalysis(ChatIntent intent, JobSearchCriteria criteria, String standaloneQuery,
                            List<Integer> ordinals, List<Long> jobIds, String source, List<String> notes) {

    public QueryAnalysis {
        ordinals = ordinals == null ? List.of() : List.copyOf(ordinals);
        jobIds = jobIds == null ? List.of() : List.copyOf(jobIds);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
