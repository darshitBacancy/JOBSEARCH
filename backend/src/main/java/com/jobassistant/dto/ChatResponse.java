package com.jobassistant.dto;

import com.jobassistant.rag.RagTrace;

import java.util.List;

/**
 * Structured chat reply: grounded text plus the data the UI renders (cards, sources, comparison).
 * {@code messageId} and {@code assistantMessageId} are the same stored reply; {@code userMessageId} is the
 * stored user message of this turn (used for feedback, edit-and-resend and regenerate).
 */
public record ChatResponse(
        String conversationId,
        Long messageId,
        String message,
        String intent,
        List<JobCardDto> jobs,
        int totalMatches,
        List<SourceRef> sources,
        ComparisonDto comparison,
        JobSearchCriteria criteria,
        List<String> relaxedFilters,
        boolean aiAvailable,
        String notice,
        Long focusJobId,
        List<String> suggestions,
        List<ToolCallRecord> toolCalls,
        RagTrace debug,
        String timestamp,
        Long userMessageId,
        Long assistantMessageId) {
}
