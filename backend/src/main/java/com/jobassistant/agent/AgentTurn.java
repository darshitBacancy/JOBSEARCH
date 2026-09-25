package com.jobassistant.agent;

import com.jobassistant.dto.ComparisonDto;
import com.jobassistant.dto.JobCardDto;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.dto.SearchFilters;
import com.jobassistant.dto.SourceRef;
import com.jobassistant.dto.ToolCallRecord;
import com.jobassistant.entity.Conversation;
import com.jobassistant.entity.Job;
import com.jobassistant.rag.RagTrace;
import com.jobassistant.search.HybridSearchService;
import com.jobassistant.search.RankedJob;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable state of one chatbot turn: what the tools returned (for the UI cards), what the
 * conversation points at (for follow-ups within the same turn), and which job ids the model
 * has legitimately seen (for the grounding guard).
 */
public class AgentTurn {

    public enum LastAction { NONE, SEARCH, REFINE, DETAILS, COMPARE }

    private final Conversation conversation;
    private final SearchFilters filters;
    private final int maxJobs;
    private final RagTrace trace;

    private JobSearchCriteria lastCriteria;
    private List<Long> candidateJobIds;
    private List<Long> shownJobIds;
    private final Set<Long> knownJobIds = new LinkedHashSet<>();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();

    private LastAction lastAction = LastAction.NONE;
    private List<JobCardDto> cards = List.of();
    private List<SourceRef> sources = List.of();
    private ComparisonDto comparison;
    private int totalMatches;
    private List<String> relaxedFilters = List.of();
    private JobSearchCriteria searchCriteria;
    private HybridSearchService.SearchOutcome searchOutcome;
    private List<RankedJob> searchTop = List.of();
    private Job detailJob;
    private Long focusJobId;

    public AgentTurn(Conversation conversation, JobSearchCriteria lastCriteria, List<Long> candidateJobIds,
                     List<Long> shownJobIds, Long focusJobId, SearchFilters filters, int maxJobs, RagTrace trace) {
        this.conversation = conversation;
        this.lastCriteria = lastCriteria;
        this.candidateJobIds = candidateJobIds == null ? List.of() : candidateJobIds;
        this.shownJobIds = shownJobIds == null ? List.of() : shownJobIds;
        this.focusJobId = focusJobId;
        this.filters = filters;
        this.maxJobs = maxJobs;
        this.trace = trace;
        knownJobIds.addAll(this.candidateJobIds);
        knownJobIds.addAll(this.shownJobIds);
        if (focusJobId != null) knownJobIds.add(focusJobId);
    }

    void onSearch(JobSearchCriteria criteria, List<Long> candidates, List<Long> shown) {
        this.lastCriteria = criteria;
        this.candidateJobIds = candidates;
        this.shownJobIds = shown;
        knownJobIds.addAll(candidates);
    }

    void recordSearch(HybridSearchService.SearchOutcome outcome, List<RankedJob> top, JobSearchCriteria criteria,
                      boolean refine, List<JobCardDto> cards, List<SourceRef> sources) {
        this.lastAction = refine ? LastAction.REFINE : LastAction.SEARCH;
        this.searchOutcome = outcome;
        this.searchTop = top;
        this.searchCriteria = criteria;
        this.totalMatches = outcome.totalMatches();
        this.relaxedFilters = outcome.relaxedFilters();
        this.cards = cards;
        this.sources = sources;
        this.comparison = null;
        top.forEach(r -> knownJobIds.add(r.job().getId()));
    }

    void recordDetails(Job job, JobCardDto card, SourceRef source) {
        this.lastAction = LastAction.DETAILS;
        this.detailJob = job;
        this.focusJobId = job.getId();
        this.cards = List.of(card);
        this.sources = List.of(source);
        this.comparison = null;
        this.totalMatches = 1;
        knownJobIds.add(job.getId());
    }

    void recordComparison(List<Job> jobs, ComparisonDto table, List<SourceRef> sources) {
        this.lastAction = LastAction.COMPARE;
        this.comparison = table;
        this.cards = table.jobs();
        this.sources = sources;
        this.totalMatches = jobs.size();
        jobs.forEach(j -> knownJobIds.add(j.getId()));
    }

    void addToolCall(ToolCallRecord record) {
        toolCalls.add(record);
    }

    public Conversation conversation() { return conversation; }
    public SearchFilters filters() { return filters; }
    public int maxJobs() { return maxJobs; }
    public RagTrace trace() { return trace; }
    public JobSearchCriteria lastCriteria() { return lastCriteria; }
    public List<Long> candidateJobIds() { return candidateJobIds; }
    public List<Long> shownJobIds() { return shownJobIds; }
    public Set<Long> knownJobIds() { return knownJobIds; }
    public List<ToolCallRecord> toolCalls() { return toolCalls; }
    public LastAction lastAction() { return lastAction; }
    public List<JobCardDto> cards() { return cards; }
    public List<SourceRef> sources() { return sources; }
    public ComparisonDto comparison() { return comparison; }
    public int totalMatches() { return totalMatches; }
    public List<String> relaxedFilters() { return relaxedFilters; }
    public JobSearchCriteria searchCriteria() { return searchCriteria; }
    public HybridSearchService.SearchOutcome searchOutcome() { return searchOutcome; }
    public List<RankedJob> searchTop() { return searchTop; }
    public Job detailJob() { return detailJob; }
    public Long focusJobId() { return focusJobId; }
}
