package com.jobassistant.search;

import com.jobassistant.ai.LocalHashingEmbeddingService;
import com.jobassistant.config.RagProperties;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.Job;
import com.jobassistant.rag.Retriever;
import com.jobassistant.rag.ScoredChunk;
import com.jobassistant.repository.JobRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Hybrid search = structured SQL filtering + vector semantic retrieval + combined ranking.
 * <pre>
 *   criteria --> SQL filters --> candidate job ids
 *   query    --> embedding  --> top-K chunks among the candidates --> group by job
 *   combine  --> RelevanceScorer --> ranked jobs
 * </pre>
 * If the hard filters leave nothing, they are relaxed one at a time (salary, experience,
 * location, employment type, remote) and the response says which filters were relaxed.
 * The topic (skills / keywords) is never relaxed, so an unknown technology yields an honest
 * empty result instead of unrelated jobs.
 */
@Service
public class HybridSearchService {

    public record SearchOutcome(List<RankedJob> ranked, int totalMatches, JobSearchCriteria appliedCriteria,
                                List<String> relaxedFilters, Retriever.Result retrieval, int structuredCandidateCount) {

        public List<RankedJob> top(int n) {
            return ranked.size() > n ? ranked.subList(0, n) : ranked;
        }
    }

    private record Relaxation(String name, UnaryOperator<JobSearchCriteria> apply) {
    }

    private static final List<Relaxation> RELAXATIONS = List.of(
            new Relaxation("salary", JobSearchCriteria::withoutSalary),
            new Relaxation("experience", JobSearchCriteria::withoutExperience),
            new Relaxation("location", JobSearchCriteria::withoutLocation),
            new Relaxation("employment type", JobSearchCriteria::withoutEmploymentType),
            new Relaxation("remote", JobSearchCriteria::withoutRemote));

    private final JobRepository jobRepository;
    private final Retriever retriever;
    private final RelevanceScorer scorer;
    private final RagProperties props;

    public HybridSearchService(JobRepository jobRepository, Retriever retriever, RelevanceScorer scorer,
                               RagProperties props) {
        this.jobRepository = jobRepository;
        this.retriever = retriever;
        this.scorer = scorer;
        this.props = props;
    }

    /**
     * @param semanticQuery    text used for vector retrieval (usually the standalone user query)
     * @param restrictToJobIds limit the search to these jobs (follow-up refinement), or null
     */
    public SearchOutcome search(JobSearchCriteria criteria, String semanticQuery, Collection<Long> restrictToJobIds) {
        SearchOutcome outcome = attempt(criteria, semanticQuery, restrictToJobIds, List.of());
        if (outcome.totalMatches() > 0 || !criteria.hasStructuredFilters()) return outcome;

        JobSearchCriteria relaxed = criteria;
        List<String> dropped = new ArrayList<>();
        for (Relaxation r : RELAXATIONS) {
            JobSearchCriteria next = r.apply().apply(relaxed);
            if (next.equals(relaxed)) continue; // this filter was not set
            relaxed = next;
            dropped.add(r.name());
            SearchOutcome attempt = attempt(relaxed, semanticQuery, restrictToJobIds, List.copyOf(dropped));
            if (attempt.totalMatches() > 0) {
                // score against the ORIGINAL criteria so the gaps explain what doesn't match
                return rescore(attempt, criteria);
            }
        }
        return outcome;
    }

    private SearchOutcome attempt(JobSearchCriteria c, String semanticQuery, Collection<Long> restrict,
                                  List<String> relaxedSoFar) {
        List<Job> candidates = jobRepository.findAll(JobSpecifications.matching(c, restrict));
        if (candidates.isEmpty()) {
            return new SearchOutcome(List.of(), 0, c, relaxedSoFar, Retriever.Result.none("No jobs passed the structured filters"), 0);
        }
        Set<Long> allowed = candidates.stream().map(Job::getId).collect(Collectors.toSet());

        boolean useSemantic = c.hasTopic() && semanticQuery != null && !semanticQuery.isBlank();
        Retriever.Result retrieval = useSemantic
                ? retriever.retrieve(semanticQuery, props.retrievalTopK(), allowed)
                : Retriever.Result.none("No topic in the query; ranked by structured filters only");

        // group retrieved chunks by job
        Map<Long, List<ScoredChunk>> byJob = new LinkedHashMap<>();
        for (ScoredChunk sc : retrieval.chunks()) byJob.computeIfAbsent(sc.record().jobId(), k -> new ArrayList<>()).add(sc);
        double best = byJob.values().stream().mapToDouble(l -> l.get(0).score()).max().orElse(0);
        boolean semanticApplies = useSemantic && !"none".equals(retrieval.mode()) && best > 0;

        List<RankedJob> ranked = new ArrayList<>();
        for (Job job : candidates) {
            List<ScoredChunk> chunks = byJob.getOrDefault(job.getId(), List.of());
            if (!passesTopicGate(job, c)) continue;
            double sim = chunks.isEmpty() ? 0 : chunks.get(0).score();
            Double semNorm = semanticApplies ? Math.max(0, sim) / best : null;
            ranked.add(scorer.score(job, c, semNorm, sim, chunks));
        }
        ranked.sort(Comparator.comparingDouble(RankedJob::score).reversed()
                .thenComparing(Comparator.comparingDouble(RankedJob::semanticSimilarity).reversed())
                .thenComparing(r -> -r.job().getSalaryMax())
                .thenComparing(r -> r.job().getPostedDate(), Comparator.nullsLast(Comparator.reverseOrder())));
        int total = ranked.size();
        List<RankedJob> pool = ranked.size() > props.candidatePoolSize()
                ? new ArrayList<>(ranked.subList(0, props.candidatePoolSize())) : ranked;
        return new SearchOutcome(pool, total, c, relaxedSoFar, retrieval, candidates.size());
    }

    /**
     * Topic gate: when the user names skills, a job must list at least one of them; when the user
     * gives only free-text keywords, the job must mention one of them (or a concrete term of the
     * keyword's concept, e.g. "cloud" -> AWS/Azure/GCP). Vector similarity alone is not enough:
     * a nearest neighbour always exists, even for a technology that no job mentions.
     */
    private boolean passesTopicGate(Job job, JobSearchCriteria c) {
        if (!c.skills().isEmpty()) {
            Set<String> jobSkills = job.getSkills().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            return c.skills().stream().anyMatch(s -> jobSkills.contains(s.toLowerCase(Locale.ROOT)));
        }
        if (!c.keywords().isEmpty()) {
            Set<String> docTokens = new HashSet<>(LocalHashingEmbeddingService.tokenize(String.join(" ",
                    job.getTitle(), String.join(" ", job.getSkills()), job.getDescription(),
                    String.join(" ", job.getRequirements()), String.join(" ", job.getResponsibilities()))));
            for (String kw : c.keywords()) {
                for (String t : LocalHashingEmbeddingService.tokenize(kw)) {
                    for (String term : LocalHashingEmbeddingService.withConcepts(t)) {
                        if (docTokens.contains(term)) return true;
                    }
                }
            }
            return false;
        }
        return true;
    }

    private SearchOutcome rescore(SearchOutcome o, JobSearchCriteria original) {
        boolean semanticApplies = o.ranked().stream().anyMatch(r -> r.breakdown().get("semantic") != null);
        double best = o.ranked().stream().mapToDouble(RankedJob::semanticSimilarity).max().orElse(0);
        List<RankedJob> rescored = new ArrayList<>();
        for (RankedJob r : o.ranked()) {
            Double semNorm = semanticApplies && best > 0 ? Math.max(0, r.semanticSimilarity()) / best : null;
            rescored.add(scorer.score(r.job(), original, semNorm, r.semanticSimilarity(), r.chunks()));
        }
        rescored.sort(Comparator.comparingDouble(RankedJob::score).reversed());
        return new SearchOutcome(rescored, o.totalMatches(), o.appliedCriteria(), o.relaxedFilters(), o.retrieval(),
                o.structuredCandidateCount());
    }
}
