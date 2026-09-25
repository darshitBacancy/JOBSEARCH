package com.jobassistant.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.AiMessage;
import com.jobassistant.ai.AiService;
import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.conversation.ChatIntent;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.rag.PromptTemplates;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI requirement extraction. Asks the LLM to turn the message into {@link JobSearchCriteria}
 * (plus intent and references), validates the result, and merges it with the deterministic
 * parser. If the LLM is unavailable or returns something unusable, the rule-based result is used.
 */
@Service
public class CriteriaExtractionService {

    private static final Logger log = LoggerFactory.getLogger(CriteriaExtractionService.class);
    private static final Set<String> EMPLOYMENT_TYPES = Set.of("FULL_TIME", "PART_TIME", "CONTRACT", "INTERNSHIP");

    /** What the extractor needs to know about the conversation so far. */
    public record ConversationContext(JobSearchCriteria previousCriteria, List<String> previouslyShownJobs,
                                      List<AiMessage> recentHistory) {
        public boolean hasPreviousResults() {
            return previouslyShownJobs != null && !previouslyShownJobs.isEmpty();
        }
    }

    private final AiService aiService;
    private final RuleBasedCriteriaParser rules;
    private final SkillCatalog skillCatalog;
    private final LocationNormalizer locations;
    private final Validator validator;
    private final ObjectMapper mapper;

    public CriteriaExtractionService(AiService aiService, RuleBasedCriteriaParser rules, SkillCatalog skillCatalog,
                                     LocationNormalizer locations, Validator validator, ObjectMapper mapper) {
        this.aiService = aiService;
        this.rules = rules;
        this.skillCatalog = skillCatalog;
        this.locations = locations;
        this.validator = validator;
        this.mapper = mapper;
    }

    public QueryAnalysis analyze(String message, ConversationContext ctx) {
        QueryAnalysis ruleResult = rules.parse(message, ctx.hasPreviousResults());
        if (!aiService.isConfigured()) {
            return withNote(ruleResult, "LLM not configured: used rule-based extraction");
        }
        try {
            String raw = aiService.chat(List.of(
                    AiMessage.system(PromptTemplates.EXTRACTION_SYSTEM),
                    AiMessage.user(buildUserPrompt(message, ctx))), AiService.ChatOptions.precise(2000));
            QueryAnalysis llm = parseLlm(raw, message);
            return merge(llm, ruleResult, ctx);
        } catch (AiUnavailableException e) {
            log.warn("LLM extraction unavailable, using rules: {}", e.getMessage());
            return withNote(ruleResult, "LLM extraction failed (" + e.getMessage() + "): used rule-based extraction");
        } catch (RuntimeException e) {
            log.warn("LLM extraction returned invalid output, using rules: {}", e.getMessage());
            return withNote(ruleResult, "LLM extraction output invalid (" + e.getMessage() + "): used rule-based extraction");
        }
    }

    private String buildUserPrompt(String message, ConversationContext ctx) throws RuntimeException {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("PREVIOUS SEARCH CRITERIA: ")
                    .append(ctx.previousCriteria() == null ? "none" : mapper.writeValueAsString(ctx.previousCriteria()))
                    .append("\n\n");
        } catch (Exception e) {
            sb.append("PREVIOUS SEARCH CRITERIA: none\n\n");
        }
        sb.append("PREVIOUSLY SHOWN JOBS:\n");
        if (!ctx.hasPreviousResults()) sb.append("none\n");
        else for (int i = 0; i < ctx.previouslyShownJobs().size(); i++) {
            sb.append(i + 1).append(". ").append(ctx.previouslyShownJobs().get(i)).append('\n');
        }
        sb.append("\nRECENT CONVERSATION:\n");
        if (ctx.recentHistory() == null || ctx.recentHistory().isEmpty()) sb.append("none\n");
        else for (AiMessage m : ctx.recentHistory()) {
            String content = m.content().length() > 400 ? m.content().substring(0, 400) + "..." : m.content();
            sb.append(m.role()).append(": ").append(content.replace('\n', ' ')).append('\n');
        }
        sb.append("\nCURRENT MESSAGE: ").append(message);
        return sb.toString();
    }

    QueryAnalysis parseLlm(String raw, String message) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("no JSON object in LLM output");
        JsonNode n;
        try {
            n = mapper.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("unparseable JSON from LLM");
        }
        ChatIntent intent;
        try {
            intent = ChatIntent.valueOf(n.path("intent").asText("NEW_SEARCH").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            intent = ChatIntent.NEW_SEARCH;
        }

        List<String> skills = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        for (String s : strings(n.path("skills"))) {
            skillCatalog.canonicalise(s).ifPresentOrElse(skills::add, () -> keywords.add(s));
        }
        keywords.addAll(strings(n.path("keywords")));

        String location = locations.canonicalise(text(n.path("location")));
        Boolean remote = n.path("remote").isBoolean() ? n.path("remote").asBoolean() : null;
        Integer expMin = intOrNull(n.path("experienceMin"), 0, 50);
        Integer expMax = intOrNull(n.path("experienceMax"), 0, 50);
        if (expMin != null && expMax != null && expMin > expMax) {
            int t = expMin;
            expMin = expMax;
            expMax = t;
        }
        Long salMin = salary(n.path("salaryMin"));
        Long salMax = salary(n.path("salaryMax"));
        String employment = text(n.path("employmentType"));
        if (employment != null) {
            employment = employment.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (!EMPLOYMENT_TYPES.contains(employment)) employment = null;
        }

        JobSearchCriteria criteria = new JobSearchCriteria(keywords, skills, location, remote, expMin, expMax,
                salMin, salMax, employment);
        Set<ConstraintViolation<JobSearchCriteria>> violations = validator.validate(criteria);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("criteria failed validation: " + violations.iterator().next().getMessage());
        }

        List<Integer> ordinals = new ArrayList<>();
        for (JsonNode o : n.path("ordinals")) if (o.canConvertToInt() && o.asInt() >= 1 && o.asInt() <= 20) ordinals.add(o.asInt());
        List<Long> jobIds = new ArrayList<>();
        for (JsonNode o : n.path("jobIds")) if (o.canConvertToLong() && o.asLong() > 0) jobIds.add(o.asLong());
        String standalone = text(n.path("standaloneQuery"));
        return new QueryAnalysis(intent, criteria, standalone == null ? message : standalone, ordinals, jobIds, "llm", List.of());
    }

    /** LLM values win when present; rule-based values fill the gaps; skills are unioned. */
    QueryAnalysis merge(QueryAnalysis llm, QueryAnalysis rule, ConversationContext ctx) {
        JobSearchCriteria l = llm.criteria();
        JobSearchCriteria r = rule.criteria();
        LinkedHashSet<String> skills = new LinkedHashSet<>(l.skills());
        skills.addAll(r.skills());
        String location = l.location() != null ? l.location() : r.location();
        List<String> keywords = rules.cleanKeywords(l.keywords().isEmpty() ? r.keywords() : l.keywords(),
                new ArrayList<>(skills), location);
        boolean llmHasExperience = l.experienceMin() != null || l.experienceMax() != null;
        JobSearchCriteria merged = new JobSearchCriteria(
                keywords,
                new ArrayList<>(skills),
                location,
                l.remote() != null ? l.remote() : r.remote(),
                llmHasExperience ? l.experienceMin() : r.experienceMin(),
                llmHasExperience ? l.experienceMax() : r.experienceMax(),
                l.salaryMin() != null ? l.salaryMin() : r.salaryMin(),
                l.salaryMax() != null ? l.salaryMax() : r.salaryMax(),
                l.employmentType() != null ? l.employmentType() : r.employmentType());

        ChatIntent intent = llm.intent();
        if (rule.intent() == ChatIntent.COMPARE) intent = ChatIntent.COMPARE; // explicit "compare" wording
        if (intent == ChatIntent.REFINE && !ctx.hasPreviousResults()) intent = ChatIntent.NEW_SEARCH;
        if (intent == ChatIntent.GENERAL && merged.hasTopic() && rule.intent() != ChatIntent.GENERAL) intent = ChatIntent.NEW_SEARCH;

        return new QueryAnalysis(intent, merged, llm.standaloneQuery(),
                llm.ordinals().isEmpty() ? rule.ordinals() : llm.ordinals(),
                llm.jobIds().isEmpty() ? rule.jobIds() : llm.jobIds(),
                "llm+rules", List.of());
    }

    private static QueryAnalysis withNote(QueryAnalysis a, String note) {
        List<String> notes = new ArrayList<>(a.notes());
        notes.add(note);
        return new QueryAnalysis(a.intent(), a.criteria(), a.standaloneQuery(), a.ordinals(), a.jobIds(), a.source(), notes);
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) for (JsonNode x : node) if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText().trim());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String s = node.asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static Integer intOrNull(JsonNode node, int min, int max) {
        if (!node.isNumber() && !(node.isTextual() && node.asText().matches("\\d+"))) return null;
        int v = node.asInt();
        return v < min || v > max ? null : v;
    }

    /** Accepts rupees; values that look like LPA (e.g. 12 or 12.5) are converted. */
    private static Long salary(JsonNode node) {
        if (!node.isNumber()) return null;
        double v = node.asDouble();
        if (v <= 0) return null;
        if (v < 1000) v = v * 100_000; // model answered in LPA
        long rupees = Math.round(v);
        return rupees > 1_000_000_000L ? null : rupees;
    }
}
