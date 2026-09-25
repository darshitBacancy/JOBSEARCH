package com.jobassistant.service;

import com.jobassistant.agent.AgentService;
import com.jobassistant.agent.AgentTurn;
import com.jobassistant.ai.AiMessage;
import com.jobassistant.ai.AiService;
import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.config.RagProperties;
import com.jobassistant.conversation.ChatIntent;
import com.jobassistant.conversation.ConversationService;
import com.jobassistant.conversation.ConversationService.ConversationState;
import com.jobassistant.dto.ChatRequest;
import com.jobassistant.dto.ChatResponse;
import com.jobassistant.dto.ComparisonDto;
import com.jobassistant.dto.JobCardDto;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.dto.SourceRef;
import com.jobassistant.entity.ChatMessage;
import com.jobassistant.entity.Conversation;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.rag.PromptTemplates;
import com.jobassistant.rag.RagService;
import com.jobassistant.rag.RagTrace;
import com.jobassistant.rag.RagTraceStore;
import com.jobassistant.rag.Retriever;
import com.jobassistant.rag.ScoredChunk;
import com.jobassistant.repository.JobRepository;
import com.jobassistant.search.CriteriaExtractionService;
import com.jobassistant.search.HybridSearchService;
import com.jobassistant.search.QueryAnalysis;
import com.jobassistant.search.RankedJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates one chat turn:
 * <pre>
 * message -> understand (LLM + rules) -> intent
 *   NEW_SEARCH / REFINE -> hybrid search -> grounded answer
 *   COMPARE             -> resolve jobs  -> comparison table + grounded summary
 *   JOB_QUESTION        -> vector retrieval within one job -> grounded answer
 *   GENERAL             -> capability/help message
 * </pre>
 * Every LLM step has a deterministic fallback, so the assistant keeps working without AI.
 */
@Service
public class ChatService {

    public static final String AI_UNAVAILABLE_NOTICE =
            "AI assistance is temporarily unavailable. I can still search the available jobs using your filters.";

    private static final List<String> DEFAULT_SUGGESTIONS = List.of(
            "Find remote Java Spring Boot jobs", "Show jobs above ₹12 LPA", "Find React jobs in Bangalore",
            "Show me remote jobs requiring AWS");

    /** Result of handling one intent. */
    private record Turn(String message, List<JobCardDto> jobs, int totalMatches, List<SourceRef> sources,
                        ComparisonDto comparison, JobSearchCriteria criteria, List<String> relaxedFilters,
                        boolean llmFailed, Long focusJobId, List<String> suggestions, List<Long> attachedJobIds) {
    }

    private final ConversationService conversations;
    private final CriteriaExtractionService extraction;
    private final RagService rag;
    private final ComparisonService comparisonService;
    private final FallbackAnswerBuilder fallback;
    private final JobRepository jobRepository;
    private final JobMapper mapper;
    private final AiService aiService;
    private final RagTraceStore traceStore;
    private final RagProperties props;
    private final AgentService agent;
    private final boolean agentMode;

    public ChatService(ConversationService conversations, CriteriaExtractionService extraction, RagService rag,
                       ComparisonService comparisonService, FallbackAnswerBuilder fallback, JobRepository jobRepository,
                       JobMapper mapper, AiService aiService, RagTraceStore traceStore, RagProperties props,
                       AgentService agent, @Value("${app.chat.mode:agent}") String chatMode) {
        this.conversations = conversations;
        this.extraction = extraction;
        this.rag = rag;
        this.comparisonService = comparisonService;
        this.fallback = fallback;
        this.jobRepository = jobRepository;
        this.mapper = mapper;
        this.aiService = aiService;
        this.traceStore = traceStore;
        this.props = props;
        this.agent = agent;
        this.agentMode = !"pipeline".equalsIgnoreCase(chatMode);
    }

    public ChatResponse handle(ChatRequest req) {
        long started = System.currentTimeMillis();
        String message = req.message().trim();
        RagTrace trace = new RagTrace();
        trace.setUserQuery(message);

        Conversation conv = conversations.getOrCreate(req.conversationId());
        trace.setConversationId(conv.getId());
        ChatMessage userMsg = conversations.addMessage(conv.getId(), ChatMessage.Role.USER, message, null);
        List<AiMessage> history = conversations.recentHistory(conv.getId(), props.historyWindow(), userMsg.getId());
        ConversationState state = conversations.state(conv);

        // 0. conversational agent: the LLM chats and calls backend tools (search / details / compare)
        if (agentMode && aiService.isConfigured()) {
            AgentTurn at = new AgentTurn(conv, state.lastCriteria(), state.candidateJobIds(), state.shownJobIds(),
                    req.focusJobId() != null ? req.focusJobId() : state.focusJobId(), req.filters(),
                    props.maxJobsReturned(), trace);
            long ta = System.currentTimeMillis();
            AgentService.Result ar = agent.run(message, history, at);
            trace.timing("agent", ta);
            trace.note(ar.note());
            if (ar.handled()) return agentResponse(req, conv, userMsg, at, ar, trace, started);
            trace.note("Falling back to the deterministic pipeline");
        }

        // 1. understand the message
        long t = System.currentTimeMillis();
        List<Job> shown = loadOrdered(state.shownJobIds());
        CriteriaExtractionService.ConversationContext ctx = new CriteriaExtractionService.ConversationContext(
                state.lastCriteria(), shown.stream().map(ChatService::describeJob).toList(), history);
        QueryAnalysis analysis = extraction.analyze(message, ctx);
        trace.timing("understanding", t);
        trace.setCriteriaSource(analysis.source());
        analysis.notes().forEach(trace::note);
        boolean extractionFailed = aiService.isConfigured() && "rules".equals(analysis.source());

        ChatIntent intent = analysis.intent();
        if (intent == ChatIntent.REFINE && !state.hasResults()) intent = ChatIntent.NEW_SEARCH;
        trace.setIntent(intent.name());

        // 2. act on the intent
        List<AiMessage> userHistory = history.stream().filter(m -> m.role().equals("user")).toList();
        userHistory = userHistory.size() > 4 ? userHistory.subList(userHistory.size() - 4, userHistory.size()) : userHistory;
        Turn turn = switch (intent) {
            case NEW_SEARCH -> search(message, analysis, req, state, conv, userHistory, trace, false);
            case REFINE -> search(message, analysis, req, state, conv, userHistory, trace, true);
            case COMPARE -> compare(message, analysis, req, state, conv, userHistory, trace);
            case JOB_QUESTION -> jobQuestion(message, analysis, req, state, conv, userHistory, trace);
            case GENERAL -> general(message, history, trace);
        };

        // 3. persist and respond
        ChatMessage reply = conversations.addMessage(conv.getId(), ChatMessage.Role.ASSISTANT, turn.message(),
                turn.attachedJobIds());
        boolean aiAvailable = aiService.isConfigured() && !extractionFailed && !turn.llmFailed();
        trace.setFinalAnswer(turn.message());
        trace.timing("total", started);
        traceStore.add(trace);

        return new ChatResponse(conv.getId(), reply.getId(), turn.message(), intent.name(), turn.jobs(),
                turn.totalMatches(), turn.sources(), turn.comparison(), turn.criteria(), turn.relaxedFilters(),
                aiAvailable, aiAvailable ? null : AI_UNAVAILABLE_NOTICE, turn.focusJobId(), turn.suggestions(),
                List.of(), req.debug() && props.debugEnabled() ? trace : null, Instant.now().toString(),
                userMsg.getId(), reply.getId());
    }

    private ChatResponse agentResponse(ChatRequest req, Conversation conv, ChatMessage userMsg, AgentTurn at,
                                       AgentService.Result ar,
                                       RagTrace trace, long started) {
        String intent = switch (at.lastAction()) {
            case SEARCH -> ChatIntent.NEW_SEARCH.name();
            case REFINE -> ChatIntent.REFINE.name();
            case DETAILS -> ChatIntent.JOB_QUESTION.name();
            case COMPARE -> ChatIntent.COMPARE.name();
            case NONE -> ChatIntent.GENERAL.name();
        };
        List<String> suggestions = switch (at.lastAction()) {
            case SEARCH, REFINE -> at.cards().isEmpty() ? DEFAULT_SUGGESTIONS : searchSuggestions(at.searchCriteria());
            case DETAILS -> List.of("What are the benefits of this job?", "What are the responsibilities?",
                    "Compare the first three jobs");
            case COMPARE -> List.of("Tell me more about the first job", "Which one pays the most?",
                    "Only show jobs with less than 5 years experience");
            case NONE -> List.of();
        };
        List<Long> attached = at.cards().stream().map(JobCardDto::id).toList();
        ChatMessage reply = conversations.addMessage(conv.getId(), ChatMessage.Role.ASSISTANT, ar.message(), attached);
        trace.setIntent(intent);
        trace.setCriteriaSource("agent (LLM tool calling)");
        trace.setLlmModel(rag.chatModel());
        trace.setAnswerSource(ar.llmFailed() ? "fallback" : "llm");
        trace.setToolCalls(at.toolCalls());
        trace.setContextSentToLlm(at.toolCalls().isEmpty() ? "(no tools called - conversational reply)"
                : "Tools called this turn:\n" + at.toolCalls().stream()
                        .map(tc -> tc.name() + " " + tc.arguments() + " -> " + tc.summary())
                        .collect(Collectors.joining("\n")));
        trace.setFinalAnswer(ar.message());
        trace.timing("total", started);
        traceStore.add(trace);
        boolean aiAvailable = !ar.llmFailed();
        return new ChatResponse(conv.getId(), reply.getId(), ar.message(), intent, at.cards(), at.totalMatches(),
                at.sources(), at.comparison(), at.searchCriteria(), at.relaxedFilters(), aiAvailable,
                aiAvailable ? null : AI_UNAVAILABLE_NOTICE, at.focusJobId(), suggestions, at.toolCalls(),
                req.debug() && props.debugEnabled() ? trace : null, Instant.now().toString(),
                userMsg.getId(), reply.getId());
    }

    // ------------------------------------------------------------------ search / refine

    private Turn search(String message, QueryAnalysis a, ChatRequest req, ConversationState state, Conversation conv,
                        List<AiMessage> history, RagTrace trace, boolean refine) {
        JobSearchCriteria criteria = refine && state.lastCriteria() != null
                ? state.lastCriteria().mergedWith(a.criteria()) : a.criteria();
        if (req.filters() != null) criteria = criteria.mergedWith(req.filters().toCriteria());
        List<Long> restrict = refine ? state.candidateJobIds() : null;
        String semanticQuery = !refine || a.source().startsWith("llm") ? a.standaloneQuery()
                : join(state.lastSemanticQuery(), message);
        trace.setCriteria(criteria);
        if (refine) trace.note("Refining the previous " + state.candidateJobIds().size() + " candidate jobs");

        long t = System.currentTimeMillis();
        HybridSearchService.SearchOutcome outcome = rag.search(criteria, semanticQuery, restrict);
        trace.timing("retrieval+ranking", t);
        trace.setRetrieval(outcome.retrieval());
        trace.setStructuredCandidateCount(outcome.structuredCandidateCount());
        trace.setRelaxedFilters(outcome.relaxedFilters());

        List<RankedJob> top = outcome.top(props.maxJobsReturned());
        trace.setSelectedJobs(top.stream().map(r -> new RagTrace.SelectedJob(r.job().getId(), r.job().getTitle(),
                r.job().getCompany(), Math.round(r.score() * 1000) / 1000.0, r.breakdown())).toList());

        if (top.isEmpty()) {
            String text = refine
                    ? "None of the previous results match that. " + fallback.noResults(criteria)
                    : fallback.noResults(criteria);
            if (!refine) conversations.saveSearchState(conv, criteria, semanticQuery, List.of(), List.of());
            return new Turn(text, List.of(), 0, List.of(), null, criteria, outcome.relaxedFilters(), false, null,
                    DEFAULT_SUGGESTIONS, List.of());
        }

        String context = rag.buildContext(top, outcome.totalMatches(), outcome.relaxedFilters(), fallback.describe(criteria));
        trace.setContextSentToLlm(context);
        Set<Long> ids = top.stream().map(r -> r.job().getId()).collect(Collectors.toSet());
        RagService.GroundedAnswer answer = generate(PromptTemplates.ANSWER_SYSTEM, context, message, List.of(), ids, trace);
        String text = answer.text() != null ? answer.text()
                : fallback.searchAnswer(top, outcome.totalMatches(), criteria, outcome.relaxedFilters());

        List<Long> shownIds = top.stream().map(r -> r.job().getId()).toList();
        conversations.saveSearchState(conv, criteria, semanticQuery,
                outcome.ranked().stream().map(r -> r.job().getId()).toList(), shownIds);

        List<JobCardDto> cards = top.stream().map(r -> mapper.toCard(r.job(), r)).toList();
        List<SourceRef> sources = top.stream().map(r -> source(r.job(), r.chunks())).toList();
        return new Turn(text, cards, outcome.totalMatches(), sources, null, criteria, outcome.relaxedFilters(),
                aiService.isConfigured() && !answer.fromLlm(), null, searchSuggestions(criteria), shownIds);
    }

    // ------------------------------------------------------------------ compare

    private Turn compare(String message, QueryAnalysis a, ChatRequest req, ConversationState state, Conversation conv,
                         List<AiMessage> history, RagTrace trace) {
        List<Long> ids = resolveCompareIds(a, req, state);
        if (ids.size() < 2) {
            // nothing to compare yet: search first, then compare the best matches
            trace.note("No previous results to compare; running a search first");
            Turn searched = search(message, a, req, state, conv, history, trace, false);
            ids = searched.attachedJobIds().stream().limit(3).toList();
            if (ids.size() < 2) {
                return new Turn("I need at least two jobs to compare. Search for jobs first, or select two or more "
                        + "jobs with the Compare button.", searched.jobs(), searched.totalMatches(), searched.sources(),
                        null, searched.criteria(), searched.relaxedFilters(), searched.llmFailed(), null,
                        DEFAULT_SUGGESTIONS, searched.attachedJobIds());
            }
        }
        List<Job> jobs = loadOrdered(ids);
        long t = System.currentTimeMillis();
        ComparisonService.ComparisonResult result = comparisonService.compare(jobs);
        trace.timing("comparison", t);
        trace.setContextSentToLlm(result.context());
        trace.setLlmModel(rag.chatModel());
        trace.setAnswerSource(result.summaryFromLlm() ? "llm" : "fallback");
        trace.note(result.note());
        trace.setSelectedJobs(jobs.stream().map(j -> new RagTrace.SelectedJob(j.getId(), j.getTitle(), j.getCompany(),
                0, Map.of())).toList());

        String refs = jobs.stream().map(j -> "Job #" + j.getId()).collect(Collectors.joining(", "));
        String text = "Here is a side-by-side comparison of " + refs + ".\n\n" + result.comparison().summary();
        List<SourceRef> sources = jobs.stream().map(j -> new SourceRef(j.getId(), j.getTitle(), j.getCompany(),
                List.of("STRUCTURED DATA"), null)).toList();
        return new Turn(text, result.comparison().jobs(), jobs.size(), sources, result.comparison(), state.lastCriteria(),
                List.of(), aiService.isConfigured() && !result.summaryFromLlm(), null,
                List.of("Tell me more about the first job", "What are the benefits of the second job?",
                        "Only show jobs with less than 5 years experience"), ids);
    }

    private List<Long> resolveCompareIds(QueryAnalysis a, ChatRequest req, ConversationState state) {
        List<Long> explicit = a.jobIds().stream().filter(jobRepository::existsById).distinct().toList();
        if (explicit.size() >= 2) return limit(explicit);
        List<Long> byOrdinal = mapOrdinals(a.ordinals(), state.shownJobIds());
        if (byOrdinal.size() >= 2) return limit(byOrdinal);
        if (req.selectedJobIds() != null) {
            List<Long> selected = req.selectedJobIds().stream().filter(jobRepository::existsById).distinct().toList();
            if (selected.size() >= 2) return limit(selected);
        }
        return state.shownJobIds().stream().limit(3).toList();
    }

    // ------------------------------------------------------------------ question about one job

    private Turn jobQuestion(String message, QueryAnalysis a, ChatRequest req, ConversationState state,
                             Conversation conv, List<AiMessage> history, RagTrace trace) {
        Long target = resolveTargetJob(a, req, state);
        if (target == null) {
            return new Turn("Which job do you mean? Search for jobs first, or open a job and use \"Ask about this job\".",
                    List.of(), 0, List.of(), null, null, List.of(), false, null, DEFAULT_SUGGESTIONS, List.of());
        }
        var found = jobRepository.findById(target);
        if (found.isEmpty()) {
            return new Turn("I couldn't find Job #" + target + " in the knowledge base.", List.of(), 0, List.of(),
                    null, null, List.of(), false, null, DEFAULT_SUGGESTIONS, List.of());
        }
        Job job = found.get();
        long t = System.currentTimeMillis();
        Retriever.Result retrieval = rag.retrieveRelevantDocuments(message, 4, Set.of(job.getId()));
        trace.timing("retrieval", t);
        trace.setRetrieval(retrieval);
        trace.setSelectedJobs(List.of(new RagTrace.SelectedJob(job.getId(), job.getTitle(), job.getCompany(), 0, Map.of())));

        String context = rag.buildJobContext(job, retrieval.chunks());
        trace.setContextSentToLlm(context);
        RagService.GroundedAnswer answer = generate(PromptTemplates.JOB_QUESTION_SYSTEM, context,
                message + "\n(The question is about Job #" + job.getId() + ".)", List.of(), Set.of(job.getId()), trace);
        String text = answer.text() != null ? answer.text() : fallback.jobAnswer(job, message);
        conversations.saveFocus(conv, job.getId());
        return new Turn(text, List.of(mapper.toCard(job)), 1, List.of(source(job, retrieval.chunks())), null, null,
                List.of(), aiService.isConfigured() && !answer.fromLlm(), job.getId(),
                List.of("What are the benefits of this job?", "What are the responsibilities?",
                        "Compare the first three jobs"), List.of(job.getId()));
    }

    private Long resolveTargetJob(QueryAnalysis a, ChatRequest req, ConversationState state) {
        for (Long id : a.jobIds()) if (jobRepository.existsById(id)) return id;
        List<Long> byOrdinal = mapOrdinals(a.ordinals(), state.shownJobIds());
        if (!byOrdinal.isEmpty()) return byOrdinal.get(0);
        if (req.focusJobId() != null) return req.focusJobId();
        if (state.focusJobId() != null) return state.focusJobId();
        return state.shownJobIds().isEmpty() ? null : state.shownJobIds().get(0);
    }

    // ------------------------------------------------------------------ general

    /**
     * Small talk and general career questions: the reply is generated by the LLM. If the AI service
     * cannot be reached, the user gets a friendly offline greeting/help message (and the response is
     * flagged aiAvailable=false) instead of a job search.
     */
    private Turn general(String message, List<AiMessage> history, RagTrace trace) {
        String reason = "no API key configured";
        if (aiService.isConfigured()) {
            try {
                List<AiMessage> messages = new ArrayList<>();
                messages.add(AiMessage.system(PromptTemplates.SMALL_TALK_SYSTEM));
                messages.addAll(history);
                messages.add(AiMessage.user(message));
                String text = aiService.chat(messages, AiService.ChatOptions.defaults());
                trace.setAnswerSource("llm");
                trace.setLlmModel(aiService.modelName());
                return new Turn(text, List.of(), 0, List.of(), null, null, List.of(), false, null, List.of(), List.of());
            } catch (AiUnavailableException e) {
                reason = e.getMessage();
                trace.note("Small-talk LLM call failed: " + e.getMessage());
            }
        }
        trace.setAnswerSource("fallback");
        trace.note("AI chat unavailable (" + reason + "): friendly offline reply");
        String text = fallback.smallTalk(message);
        return new Turn(text, List.of(), 0, List.of(), null, null, List.of(), aiService.isConfigured(), null,
                DEFAULT_SUGGESTIONS, List.of());
    }

    // ------------------------------------------------------------------ helpers

    private RagService.GroundedAnswer generate(String system, String context, String question, List<AiMessage> history,
                                               Set<Long> ids, RagTrace trace) {
        long t = System.currentTimeMillis();
        RagService.GroundedAnswer answer = rag.generateGroundedAnswer(system, context, question, history, ids);
        trace.timing("generation", t);
        trace.setLlmModel(rag.chatModel());
        trace.setAnswerSource(answer.fromLlm() ? "llm" : "fallback");
        trace.note(answer.note());
        return answer;
    }

    private SourceRef source(Job job, List<ScoredChunk> chunks) {
        List<String> sections = chunks.stream().map(c -> c.record().section()).distinct().toList();
        Double sim = chunks.isEmpty() ? null : Math.round(chunks.get(0).score() * 1000) / 1000.0;
        return new SourceRef(job.getId(), job.getTitle(), job.getCompany(),
                sections.isEmpty() ? List.of("STRUCTURED DATA") : sections, sim);
    }

    private List<Job> loadOrdered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, Job> byId = jobRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private static List<Long> mapOrdinals(List<Integer> ordinals, List<Long> shown) {
        List<Long> out = new ArrayList<>();
        for (Integer o : ordinals) {
            int idx = o == -1 ? shown.size() - 1 : o - 1;
            if (idx >= 0 && idx < shown.size()) out.add(shown.get(idx));
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private static List<Long> limit(List<Long> ids) {
        return ids.size() > 4 ? ids.subList(0, 4) : ids;
    }

    private static String describeJob(Job j) {
        return "Job #" + j.getId() + " " + j.getTitle() + " - " + j.getCompany() + " (" + j.getLocation()
                + (j.isRemote() ? ", remote" : "") + ", "
                + JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency()) + ")";
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : a + ". " + b;
    }

    private static List<String> searchSuggestions(JobSearchCriteria c) {
        List<String> s = new ArrayList<>();
        s.add("Compare the first three jobs");
        if (c.salaryMin() == null) s.add("Only those above ₹15 LPA");
        else if (!Boolean.TRUE.equals(c.remote())) s.add("Only remote ones");
        else s.add("Only full-time roles");
        s.add("What skills does the first job require?");
        if (c.experienceMax() == null) s.add("Only show jobs with less than 5 years experience");
        return s;
    }
}
