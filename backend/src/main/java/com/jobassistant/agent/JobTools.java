package com.jobassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobassistant.ai.ToolCall;
import com.jobassistant.ai.ToolDefinition;
import com.jobassistant.conversation.ConversationService;
import com.jobassistant.dto.ComparisonDto;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.dto.SourceRef;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.rag.RagService;
import com.jobassistant.rag.RagTrace;
import com.jobassistant.rag.Retriever;
import com.jobassistant.rag.ScoredChunk;
import com.jobassistant.repository.JobRepository;
import com.jobassistant.search.HybridSearchService;
import com.jobassistant.search.LocationNormalizer;
import com.jobassistant.search.RankedJob;
import com.jobassistant.search.RuleBasedCriteriaParser;
import com.jobassistant.search.SkillCatalog;
import com.jobassistant.service.ComparisonService;
import com.jobassistant.service.FallbackAnswerBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The backend functions the chatbot (LLM) can call. Every tool reads from the database / vector
 * store - the tools are the only way job facts reach the model.
 */
@Component
public class JobTools {

    public static final String SEARCH_JOBS = "search_jobs";
    public static final String GET_JOB_DETAILS = "get_job_details";
    public static final String COMPARE_JOBS = "compare_jobs";

    private final RagService rag;
    private final JobRepository jobRepository;
    private final ComparisonService comparisonService;
    private final ConversationService conversations;
    private final RuleBasedCriteriaParser parser;
    private final SkillCatalog skillCatalog;
    private final LocationNormalizer locations;
    private final FallbackAnswerBuilder describer;
    private final JobMapper mapper;
    private final ObjectMapper json;

    public JobTools(RagService rag, JobRepository jobRepository, ComparisonService comparisonService,
                    ConversationService conversations, RuleBasedCriteriaParser parser, SkillCatalog skillCatalog,
                    LocationNormalizer locations, FallbackAnswerBuilder describer, JobMapper mapper, ObjectMapper json) {
        this.rag = rag;
        this.jobRepository = jobRepository;
        this.comparisonService = comparisonService;
        this.conversations = conversations;
        this.parser = parser;
        this.skillCatalog = skillCatalog;
        this.locations = locations;
        this.describer = describer;
        this.mapper = mapper;
        this.json = json;
    }

    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition(SEARCH_JOBS,
                        "Search the job knowledge base (hybrid: structured filters + semantic vector search). "
                                + "Call this whenever the user wants to find, list, filter or narrow down jobs.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description",
                                                "Natural-language description of the wanted jobs, e.g. 'remote Java Spring Boot backend roles'"),
                                        "skills", Map.of("type", "array", "items", Map.of("type", "string"), "description",
                                                "Required technologies, e.g. [\"Java\", \"AWS\"]"),
                                        "location", Map.of("type", "string", "description", "City such as Bangalore or Pune, or 'India'"),
                                        "remote", Map.of("type", "boolean", "description", "true only if the user wants remote jobs"),
                                        "experience_years", Map.of("type", "integer", "description", "The user's own years of experience"),
                                        "max_required_experience", Map.of("type", "integer", "description",
                                                "Only jobs whose minimum required experience is at most this, e.g. 'less than 5 years' -> 4"),
                                        "min_salary_lpa", Map.of("type", "number", "description",
                                                "Minimum annual salary in LPA (lakhs per annum), e.g. 12"),
                                        "employment_type", Map.of("type", "string", "enum",
                                                List.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP")),
                                        "refine_previous", Map.of("type", "boolean", "description",
                                                "true to narrow down the previous search results (e.g. 'only those above 15 LPA')")),
                                "required", List.of("query"))),
                new ToolDefinition(GET_JOB_DETAILS,
                        "Get the full listing of one job (description, requirements, responsibilities, benefits, link) "
                                + "plus the parts most relevant to the user's question. Use for questions about a specific job.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "job_id", Map.of("type", "integer", "description", "The job id, e.g. 118"),
                                        "question", Map.of("type", "string", "description",
                                                "What the user wants to know about this job")),
                                "required", List.of("job_id"))),
                new ToolDefinition(COMPARE_JOBS,
                        "Compare 2 to 4 jobs side by side (title, company, location, remote, experience, salary, skills, "
                                + "employment type, benefits).",
                        Map.of("type", "object",
                                "properties", Map.of("job_ids", Map.of("type", "array", "items", Map.of("type", "integer"),
                                        "minItems", 2, "maxItems", 4)),
                                "required", List.of("job_ids"))));
    }

    /** Executes one tool call and returns the JSON result that is sent back to the model. */
    public String execute(ToolCall call, AgentTurn turn) {
        JsonNode args;
        try {
            args = json.readTree(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
        } catch (Exception e) {
            return error("arguments are not valid JSON");
        }
        return switch (call.name()) {
            case SEARCH_JOBS -> searchJobs(args, turn);
            case GET_JOB_DETAILS -> jobDetails(args, turn);
            case COMPARE_JOBS -> compareJobs(args, turn);
            default -> error("unknown tool '" + call.name() + "'");
        };
    }

    // ------------------------------------------------------------------ search_jobs

    private String searchJobs(JsonNode a, AgentTurn turn) {
        String query = text(a, "query");
        List<String> skills = new ArrayList<>();
        List<String> unknownSkills = new ArrayList<>();
        for (JsonNode s : a.path("skills")) {
            if (!s.isTextual() || s.asText().isBlank()) continue;
            skillCatalog.canonicalise(s.asText()).ifPresentOrElse(skills::add, () -> unknownSkills.add(s.asText()));
        }
        Integer years = integer(a, "experience_years");
        Integer maxRequired = integer(a, "max_required_experience");
        Double lpa = number(a, "min_salary_lpa");
        String employment = text(a, "employment_type");
        if (employment != null) {
            employment = employment.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (!Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP").contains(employment)) employment = null;
        }
        JobSearchCriteria explicit = new JobSearchCriteria(unknownSkills, skills,
                locations.canonicalise(text(a, "location")),
                a.path("remote").asBoolean(false) ? Boolean.TRUE : null,  // "remote=false" is not treated as "on-site only"
                years != null ? years : null,
                years != null ? years : maxRequired,
                lpa != null && lpa > 0 ? Math.round(lpa * 100_000) : null, null, employment);

        // the free-text query is parsed too, so keywords/filters mentioned only there are not lost
        JobSearchCriteria criteria = query == null ? explicit
                : parser.parse(query, false).criteria().mergedWith(explicit);
        boolean refine = a.path("refine_previous").asBoolean(false) && !turn.candidateJobIds().isEmpty();
        if (refine && turn.lastCriteria() != null) criteria = turn.lastCriteria().mergedWith(criteria);
        if (turn.filters() != null) criteria = criteria.mergedWith(turn.filters().toCriteria());
        String semantic = query != null ? query : String.join(" ", criteria.skills());

        HybridSearchService.SearchOutcome outcome = rag.search(criteria, semantic, refine ? turn.candidateJobIds() : null);
        List<RankedJob> top = outcome.top(turn.maxJobs());
        List<Long> shownIds = top.stream().map(r -> r.job().getId()).toList();
        List<Long> candidateIds = outcome.ranked().stream().map(r -> r.job().getId()).toList();
        if (!top.isEmpty() || !refine) {
            conversations.saveSearchState(turn.conversation(), criteria, semantic, candidateIds, shownIds);
            turn.onSearch(criteria, candidateIds, shownIds);
        }
        turn.recordSearch(outcome, top, criteria, refine,
                top.stream().map(r -> mapper.toCard(r.job(), r)).toList(),
                top.stream().map(r -> source(r.job(), r.chunks())).toList());

        RagTrace trace = turn.trace();
        trace.setCriteria(criteria);
        trace.setRetrieval(outcome.retrieval());
        trace.setStructuredCandidateCount(outcome.structuredCandidateCount());
        trace.setRelaxedFilters(outcome.relaxedFilters());
        trace.setSelectedJobs(top.stream().map(r -> new RagTrace.SelectedJob(r.job().getId(), r.job().getTitle(),
                r.job().getCompany(), Math.round(r.score() * 1000) / 1000.0, r.breakdown())).toList());

        ObjectNode out = json.createObjectNode();
        out.put("total_matches", outcome.totalMatches());
        out.put("criteria_applied", describer.describe(criteria));
        out.set("relaxed_filters", json.valueToTree(outcome.relaxedFilters()));
        if (top.isEmpty()) out.put("note", "No jobs in the knowledge base match this search.");
        ArrayNode jobs = out.putArray("jobs");
        for (RankedJob r : top) {
            ObjectNode j = facts(r.job());
            j.put("match_score_percent", r.matchPercent());
            j.set("why_it_matches", json.valueToTree(r.reasons()));
            j.set("gaps", json.valueToTree(r.gaps()));
            if (!r.chunks().isEmpty()) j.put("most_relevant_excerpt", excerpt(r.chunks().get(0)));
            jobs.add(j);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ get_job_details

    private String jobDetails(JsonNode a, AgentTurn turn) {
        Integer id = integer(a, "job_id");
        if (id == null) return error("job_id is required");
        Optional<Job> found = jobRepository.findById(id.longValue());
        if (found.isEmpty()) return error("Job #" + id + " does not exist in the knowledge base");
        Job job = found.get();
        String question = Optional.ofNullable(text(a, "question")).orElse("job overview requirements skills");
        Retriever.Result retrieval = rag.retrieveRelevantDocuments(question, 4, Set.of(job.getId()));
        turn.trace().setRetrieval(retrieval);
        conversations.saveFocus(turn.conversation(), job.getId());
        turn.recordDetails(job, mapper.toCard(job), source(job, retrieval.chunks()));

        ObjectNode out = facts(job);
        out.put("description", job.getDescription());
        out.set("requirements", json.valueToTree(job.getRequirements()));
        out.set("responsibilities", json.valueToTree(job.getResponsibilities()));
        out.set("benefits", json.valueToTree(job.getBenefits()));
        out.put("application_url", job.getApplicationUrl());
        out.put("posted_date", String.valueOf(job.getPostedDate()));
        ArrayNode ex = out.putArray("most_relevant_excerpts");
        for (ScoredChunk c : retrieval.chunks()) {
            ObjectNode e = ex.addObject();
            e.put("section", c.record().section());
            e.put("similarity", Math.round(c.score() * 1000) / 1000.0);
            e.put("text", excerpt(c));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ compare_jobs

    private String compareJobs(JsonNode a, AgentTurn turn) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (JsonNode n : a.path("job_ids")) if (n.canConvertToLong()) ids.add(n.asLong());
        if (ids.size() < 2 || ids.size() > 4) return error("provide 2 to 4 different job ids");
        List<Job> jobs = new ArrayList<>();
        for (Long id : ids) {
            Optional<Job> j = jobRepository.findById(id);
            if (j.isEmpty()) return error("Job #" + id + " does not exist in the knowledge base");
            jobs.add(j.get());
        }
        ComparisonDto table = comparisonService.table(jobs);
        turn.recordComparison(jobs, table, jobs.stream().map(j -> new SourceRef(j.getId(), j.getTitle(),
                j.getCompany(), List.of("STRUCTURED DATA"), null)).toList());

        ObjectNode out = json.createObjectNode();
        ArrayNode arr = out.putArray("jobs");
        for (Job j : jobs) {
            ObjectNode o = facts(j);
            o.set("benefits", json.valueToTree(j.getBenefits()));
            arr.add(o);
        }
        out.put("computed_facts", table.summary());
        return out.toString();
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode facts(Job j) {
        ObjectNode o = json.createObjectNode();
        o.put("job_id", j.getId());
        o.put("title", j.getTitle());
        o.put("company", j.getCompany());
        o.put("location", j.getLocation());
        o.put("remote", j.isRemote());
        o.put("employment_type", JobMapper.employmentTypeLabel(j.getEmploymentType()));
        o.put("experience_required", JobMapper.formatExperience(j.getExperienceMin(), j.getExperienceMax()));
        o.put("salary", JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency()));
        o.set("skills", json.valueToTree(j.getSkills()));
        return o;
    }

    private static SourceRef source(Job job, List<ScoredChunk> chunks) {
        List<String> sections = chunks.stream().map(c -> c.record().section()).distinct().toList();
        Double sim = chunks.isEmpty() ? null : Math.round(chunks.get(0).score() * 1000) / 1000.0;
        return new SourceRef(job.getId(), job.getTitle(), job.getCompany(),
                sections.isEmpty() ? List.of("STRUCTURED DATA") : sections, sim);
    }

    private static String excerpt(ScoredChunk c) {
        String t = c.record().chunkText();
        if (t.startsWith("Job: ") && t.contains("\n")) t = t.substring(t.indexOf('\n') + 1);
        t = t.replace('\n', ' ');
        return t.length() > 400 ? t.substring(0, 400) + "..." : t;
    }

    private String error(String message) {
        return json.createObjectNode().put("error", message).toString();
    }

    private static String text(JsonNode a, String field) {
        JsonNode n = a.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static Integer integer(JsonNode a, String field) {
        JsonNode n = a.path(field);
        if (n.isNumber()) return n.asInt();
        if (n.isTextual() && n.asText().trim().matches("\\d+")) return Integer.parseInt(n.asText().trim());
        return null;
    }

    private static Double number(JsonNode a, String field) {
        JsonNode n = a.path(field);
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual() && n.asText().trim().matches("\\d+(\\.\\d+)?")) return Double.parseDouble(n.asText().trim());
        return null;
    }
}
