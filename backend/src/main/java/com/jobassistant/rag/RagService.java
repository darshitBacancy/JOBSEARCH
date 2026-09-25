package com.jobassistant.rag;

import com.jobassistant.ai.AiMessage;
import com.jobassistant.ai.AiService;
import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.dto.IndexStatusDto;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.search.HybridSearchService;
import com.jobassistant.search.RankedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facade over the RAG pipeline, independent of any controller:
 * <ul>
 *   <li>{@link #indexDocuments()} - (re)build the vector index</li>
 *   <li>{@link #retrieveRelevantDocuments} - vector similarity search</li>
 *   <li>{@link #search} - hybrid structured + semantic search</li>
 *   <li>{@link #buildContext} - turn retrieved jobs into the grounding context</li>
 *   <li>{@link #generateGroundedAnswer} - ask the LLM to answer from that context only</li>
 * </ul>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final Pattern JOB_REF = Pattern.compile("(?:#|\\bjob\\s+(?:id\\s*)?#?)\\s*(\\d{2,})", Pattern.CASE_INSENSITIVE);

    /** Outcome of generation; {@code text} is null when the LLM could not be used. */
    public record GroundedAnswer(String text, boolean fromLlm, String note) {
    }

    private final IndexingService indexingService;
    private final Retriever retriever;
    private final HybridSearchService hybridSearch;
    private final AiService aiService;

    public RagService(IndexingService indexingService, Retriever retriever, HybridSearchService hybridSearch,
                      AiService aiService) {
        this.indexingService = indexingService;
        this.retriever = retriever;
        this.hybridSearch = hybridSearch;
        this.aiService = aiService;
    }

    public IndexStatusDto indexDocuments() {
        return indexingService.reindex();
    }

    public Retriever.Result retrieveRelevantDocuments(String query, int topK, Set<Long> allowedJobIds) {
        return retriever.retrieve(query, topK, allowedJobIds);
    }

    public HybridSearchService.SearchOutcome search(JobSearchCriteria criteria, String semanticQuery,
                                                    Collection<Long> restrictToJobIds) {
        return hybridSearch.search(criteria, semanticQuery, restrictToJobIds);
    }

    /** Grounding context for a search answer: only the top-ranked jobs, never the whole database. */
    public String buildContext(List<RankedJob> jobs, int totalMatches, List<String> relaxedFilters,
                               String appliedCriteria) {
        StringBuilder sb = new StringBuilder("JOB CONTEXT (ranked by relevance, best first):\n\n");
        for (RankedJob r : jobs) {
            sb.append(jobFacts(r.job()));
            sb.append("Match assessment (computed by the search engine, relative): ").append(r.matchPercent())
                    .append("% - ").append(r.matchLabel()).append('\n');
            if (!r.reasons().isEmpty()) sb.append("Why it matches: ").append(String.join("; ", r.reasons())).append('\n');
            if (!r.gaps().isEmpty()) sb.append("Gaps: ").append(String.join("; ", r.gaps())).append('\n');
            appendExcerpts(sb, r.chunks());
            sb.append('\n');
        }
        sb.append("SEARCH SUMMARY: criteria applied by the search engine: ")
                .append(appliedCriteria == null || appliedCriteria.isBlank() ? "none" : appliedCriteria).append(". ")
                .append(totalMatches).append(" job(s) matched in total; ")
                .append(jobs.size()).append(" shown above.");
        if (relaxedFilters != null && !relaxedFilters.isEmpty()) {
            sb.append(" No job matched every filter, so these filters were relaxed: ")
                    .append(String.join(", ", relaxedFilters)).append('.');
        }
        return sb.toString();
    }

    /** Grounding context for a question about a single job: facts plus the chunks most similar to the question. */
    public String buildJobContext(Job job, List<ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder("JOB CONTEXT:\n\n").append(jobFacts(job));
        sb.append("Description: ").append(job.getDescription()).append('\n');
        appendExcerpts(sb, chunks);
        return sb.toString();
    }

    /** Full facts of several jobs, used for comparisons. */
    public String buildComparisonContext(List<Job> jobs) {
        StringBuilder sb = new StringBuilder("JOB CONTEXT:\n\n");
        for (Job j : jobs) {
            sb.append(jobFacts(j));
            sb.append("Benefits: ").append(String.join("; ", j.getBenefits())).append("\n\n");
        }
        return sb.toString();
    }

    private static String jobFacts(Job j) {
        return "[JOB #" + j.getId() + "]\n"
                + "Title: " + j.getTitle() + '\n'
                + "Company: " + j.getCompany() + '\n'
                + "Location: " + j.getLocation() + '\n'
                + "Remote: " + (j.isRemote() ? "Yes" : "No") + '\n'
                + "Employment type: " + JobMapper.employmentTypeLabel(j.getEmploymentType()) + '\n'
                + "Experience required: " + JobMapper.formatExperience(j.getExperienceMin(), j.getExperienceMax()) + '\n'
                + "Salary: " + JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency()) + '\n'
                + "Skills: " + String.join(", ", j.getSkills()) + '\n'
                + "Posted: " + j.getPostedDate() + '\n';
    }

    private static void appendExcerpts(StringBuilder sb, List<ScoredChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        sb.append("Retrieved excerpts:\n");
        chunks.stream().limit(3).forEach(c -> sb.append("  (").append(c.record().section()).append(", similarity ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", c.score())).append(") ")
                .append(stripHeader(c.record().chunkText()).replace("\n", "\n    ")).append('\n'));
    }

    private static String stripHeader(String chunkText) {
        return chunkText.startsWith("Job: ") && chunkText.contains("\n") ? chunkText.substring(chunkText.indexOf('\n') + 1) : chunkText;
    }

    /**
     * Ask the LLM to answer using only the context. The answer is rejected (and the caller falls
     * back to a deterministic answer) if it references a job id that is not in the context.
     */
    public GroundedAnswer generateGroundedAnswer(String systemPrompt, String context, String question,
                                                 List<AiMessage> history, Set<Long> allowedJobIds) {
        if (!aiService.isConfigured()) {
            return new GroundedAnswer(null, false, "LLM not configured");
        }
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(systemPrompt));
        if (history != null) messages.addAll(history);
        messages.add(AiMessage.user(context + "\n\nUSER QUESTION:\n" + question));
        try {
            String answer = aiService.chat(messages, AiService.ChatOptions.defaults());
            Set<Long> unknown = referencedJobIds(answer);
            unknown.removeAll(allowedJobIds);
            if (!unknown.isEmpty()) {
                log.warn("Grounding guard rejected LLM answer referencing unknown jobs {}", unknown);
                return new GroundedAnswer(null, false, "Grounding guard: LLM referenced job(s) " + unknown
                        + " that are not in the retrieved context; answer discarded");
            }
            return new GroundedAnswer(answer, true, null);
        } catch (AiUnavailableException e) {
            log.warn("Grounded generation failed: {}", e.getMessage());
            return new GroundedAnswer(null, false, "LLM generation failed: " + e.getMessage());
        }
    }

    static Set<Long> referencedJobIds(String text) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher m = JOB_REF.matcher(text);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }

    public String chatModel() {
        return aiService.modelName();
    }
}
