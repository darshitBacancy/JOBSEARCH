package com.jobassistant.rag;

import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.dto.ToolCallRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Observability record for one chat turn: query -> criteria -> retrieved chunks -> selected
 * jobs -> context sent to the LLM -> final answer. Returned to the UI's "RAG Inspector" when
 * debugging is enabled. Contains no secrets.
 */
public class RagTrace {

    public record RetrievedChunk(String chunkId, long jobId, String section, double similarity, String preview) {
    }

    public record SelectedJob(long jobId, String title, String company, double score, Map<String, Double> breakdown) {
    }

    private final String traceId = UUID.randomUUID().toString();
    private final String timestamp = Instant.now().toString();
    private String conversationId;
    private String userQuery;
    private String intent;
    private String criteriaSource;
    private JobSearchCriteria criteria;
    private String embeddingProvider;
    private String embeddingModel;
    private String retrievalMode;
    private int structuredCandidateCount;
    private List<RetrievedChunk> retrievedChunks = new ArrayList<>();
    private List<SelectedJob> selectedJobs = new ArrayList<>();
    private List<String> relaxedFilters = new ArrayList<>();
    private String contextSentToLlm;
    private String llmModel;
    private String answerSource = "none";
    private String finalAnswer;
    private final Map<String, Long> timingsMs = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private List<ToolCallRecord> toolCalls = new ArrayList<>();

    public void timing(String step, long startMillis) {
        timingsMs.put(step, System.currentTimeMillis() - startMillis);
    }

    public void note(String note) {
        if (note != null && !note.isBlank()) notes.add(note);
    }

    public void setRetrieval(Retriever.Result r) {
        this.retrievalMode = r.mode();
        this.embeddingProvider = r.embeddingProvider();
        this.embeddingModel = r.embeddingModel();
        this.retrievedChunks = r.chunks().stream().map(c -> new RetrievedChunk(c.record().documentId(),
                c.record().jobId(), c.record().section(), Math.round(c.score() * 10000) / 10000.0,
                preview(c.record().chunkText()))).toList();
        note(r.note());
    }

    private static String preview(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() > 220 ? flat.substring(0, 220) + "..." : flat;
    }

    public String getTraceId() { return traceId; }
    public String getTimestamp() { return timestamp; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getUserQuery() { return userQuery; }
    public void setUserQuery(String userQuery) { this.userQuery = userQuery; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getCriteriaSource() { return criteriaSource; }
    public void setCriteriaSource(String criteriaSource) { this.criteriaSource = criteriaSource; }
    public JobSearchCriteria getCriteria() { return criteria; }
    public void setCriteria(JobSearchCriteria criteria) { this.criteria = criteria; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public String getEmbeddingModel() { return embeddingModel; }
    public String getRetrievalMode() { return retrievalMode; }
    public int getStructuredCandidateCount() { return structuredCandidateCount; }
    public void setStructuredCandidateCount(int structuredCandidateCount) { this.structuredCandidateCount = structuredCandidateCount; }
    public List<RetrievedChunk> getRetrievedChunks() { return retrievedChunks; }
    public List<SelectedJob> getSelectedJobs() { return selectedJobs; }
    public void setSelectedJobs(List<SelectedJob> selectedJobs) { this.selectedJobs = selectedJobs; }
    public List<String> getRelaxedFilters() { return relaxedFilters; }
    public void setRelaxedFilters(List<String> relaxedFilters) { this.relaxedFilters = relaxedFilters; }
    public String getContextSentToLlm() { return contextSentToLlm; }
    public void setContextSentToLlm(String contextSentToLlm) { this.contextSentToLlm = contextSentToLlm; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public String getAnswerSource() { return answerSource; }
    public void setAnswerSource(String answerSource) { this.answerSource = answerSource; }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
    public Map<String, Long> getTimingsMs() { return timingsMs; }
    public List<String> getNotes() { return notes; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallRecord> toolCalls) { this.toolCalls = toolCalls; }
}
