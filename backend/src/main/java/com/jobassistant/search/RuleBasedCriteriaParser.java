package com.jobassistant.search;

import com.jobassistant.ai.LocalHashingEmbeddingService;
import com.jobassistant.conversation.ChatIntent;
import com.jobassistant.dto.JobSearchCriteria;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic query understanding. It runs on every message: its output is merged with the
 * LLM extraction, and it is the complete fallback when the LLM is unavailable.
 */
@Component
public class RuleBasedCriteriaParser {

    private static final String AMOUNT = "(?:₹|rs\\.?|inr)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:lpa|lakhs?|lacs?|l)(?![a-z])";
    private static final Pattern SALARY_RANGE = Pattern.compile(
            "(?:₹|rs\\.?|inr)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:-|to)\\s*(?:₹|rs\\.?|inr)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:lpa|lakhs?|lacs?|l)(?![a-z])");
    private static final Pattern SALARY_MIN = Pattern.compile(
            "(?:above|over|more than|greater than|at least|atleast|minimum(?: of)?|min\\.?|>=?|upwards of|starting(?: at| from)?|from|\\bof)\\s*" + AMOUNT);
    private static final Pattern SALARY_MAX = Pattern.compile(
            "(?:below|under|less than|up to|upto|at most|maximum(?: of)?|max\\.?|<=?)\\s*" + AMOUNT);
    private static final Pattern SALARY_BARE = Pattern.compile(AMOUNT);

    private static final String YEARS = "\\s*\\+?\\s*(?:years?|yrs?)";
    private static final Pattern EXP_RANGE = Pattern.compile("(\\d{1,2})\\s*(?:-|to)\\s*(\\d{1,2})" + YEARS);
    private static final Pattern EXP_LESS = Pattern.compile("(?:less than|under|below|fewer than|<)\\s*(\\d{1,2})" + YEARS);
    private static final Pattern EXP_ATMOST = Pattern.compile("(?:up to|upto|at most|maximum(?: of)?|max\\.?)\\s*(\\d{1,2})" + YEARS);
    private static final Pattern EXP_MORE = Pattern.compile("(?:more than|over|at least|atleast|minimum(?: of)?|min\\.?|above)\\s*(\\d{1,2})" + YEARS);
    private static final Pattern EXP_PLUS = Pattern.compile("(\\d{1,2})\\s*\\+\\s*(?:years?|yrs?)");
    private static final Pattern EXP_EXACT = Pattern.compile("(\\d{1,2})\\s*(?:years?|yrs?)");

    private static final Pattern REMOTE = Pattern.compile("\\b(remote|work from home|wfh|work-from-home|remotely)\\b");
    private static final Pattern ONSITE = Pattern.compile("\\b(on-?site|in-office|in office|office-based|not remote)\\b");

    private static final Pattern COMPARE = Pattern.compile("\\b(compar(e|ison|ing)|vs\\.?|versus|difference between|side by side)\\b");
    private static final Pattern JOB_REFERENCE = Pattern.compile(
            "\\b(this|that|the (first|second|third|fourth|fifth|last|top)|1st|2nd|3rd|4th|5th)\\s+(job|role|position|one|opening|listing)\\b"
                    + "|\\bjob\\s*#?\\s*\\d+|#\\d+|\\b(it|this one)\\b");
    private static final Pattern QUESTION = Pattern.compile(
            "\\?|\\b(what|which|does|do|is|are|how|tell me|explain|describe|more about|details|skills|benefits|requirements|responsibilit|salary|apply|require)");
    private static final Pattern REFINE = Pattern.compile(
            "^(only|just|and|but|now|also|what about|how about|filter|narrow|exclude|remove|same)\\b"
                    + "|\\b(those|these|them|of them|among them|from these|from those|from the above|above results|previous results|same search|the results|the list)\\b");
    private static final Pattern JOB_VOCABULARY = Pattern.compile(
            "\\b(jobs?|roles?|positions?|openings?|vacanc|hiring|hire|career|work|opportunit|intern|salary|lpa|ctc|"
                    + "experience|years?|develop|engineer|programm|design|analyst|architect|manager|lead|consultant|"
                    + "backend|frontend|full.?stack|devops|sre|cloud|data|mobile|qa|test|security|machine learning|"
                    + "ml|ai|fintech|startup|company|companies|remote|onsite|hybrid|find|search|show|looking|recommend|"
                    + "apply|offer)");
    private static final Pattern GREETING = Pattern.compile(
            "^(hi|hello|hey|hola|namaste|good (morning|afternoon|evening)|thanks|thank you|ok|okay|cool|great|help|who are you|what can you do)\\b.*");

    private static final Map<String, Integer> ORDINALS = Map.ofEntries(
            Map.entry("first", 1), Map.entry("1st", 1), Map.entry("second", 2), Map.entry("2nd", 2),
            Map.entry("third", 3), Map.entry("3rd", 3), Map.entry("fourth", 4), Map.entry("4th", 4),
            Map.entry("fifth", 5), Map.entry("5th", 5));
    private static final Map<String, Integer> NUMBER_WORDS = Map.of(
            "two", 2, "three", 3, "four", 4, "five", 5, "2", 2, "3", 3, "4", 4, "5", 5);
    private static final Pattern FIRST_N = Pattern.compile("\\b(?:first|top|best)\\s+(two|three|four|five|2|3|4|5)\\b");
    private static final Pattern ORDINAL_WORD = Pattern.compile("\\b(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\\b");
    private static final Pattern EXPLICIT_ID = Pattern.compile("(?:#|\\bjob\\s*#?\\s*|\\bid\\s*)(\\d{3,})");
    private static final Pattern SMALL_NUMBER = Pattern.compile("\\b(?:job|jobs|number|no\\.?)?\\s*([1-5])\\b(?!\\s*(?:years?|yrs?|lpa|lakh|\\+|-))");

    /** Words that describe the search itself rather than the job topic. */
    private static final Set<String> NON_TOPIC = Set.of(
            "job", "role", "position", "opening", "opportunity", "opportunitie", "vacancy", "vacancie", "work",
            "remote", "hybrid", "onsite", "office", "salary", "lpa", "lakh", "lakhs", "lac", "inr", "rs", "year",
            "years", "yrs", "experience", "exp", "above", "below", "over", "under", "less", "more", "than", "least",
            "most", "minimum", "maximum", "min", "max", "between", "only", "just", "also", "show", "list", "give",
            "get", "search", "look", "compare", "comparison", "versus", "vs", "first", "second", "third", "top",
            "best", "matching", "match", "engineer", "fresher", "india", "city", "location", "based", "full", "time",
            "part", "contract", "internship", "intern", "permanent", "those", "these", "them", "then", "now", "like",
            "would", "could", "should", "many", "much", "new", "good", "great", "hi", "hello", "hey", "thank",
            "thanks", "ok", "okay", "per", "annum", "month", "high", "higher", "paying", "pay", "package", "ctc",
            "offer", "available", "current", "currently", "id", "require", "requiring", "required", "requirement",
            "skill", "skilled", "knowledge", "background", "someone", "people", "plus", "etc", "tell", "know");

    private final SkillCatalog skillCatalog;
    private final LocationNormalizer locationNormalizer;

    public RuleBasedCriteriaParser(SkillCatalog skillCatalog, LocationNormalizer locationNormalizer) {
        this.skillCatalog = skillCatalog;
        this.locationNormalizer = locationNormalizer;
    }

    public QueryAnalysis parse(String message, boolean hasPreviousResults) {
        String text = message == null ? "" : message.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        List<String> skills = skillCatalog.detect(text);
        String location = locationNormalizer.detect(text).orElse(null);

        Boolean remote = null;
        if (ONSITE.matcher(lower).find()) remote = false;
        else if (REMOTE.matcher(lower).find()) remote = true;

        Long salaryMin = null;
        Long salaryMax = null;
        Matcher m;
        if ((m = SALARY_RANGE.matcher(lower)).find()) {
            salaryMin = lakhs(m.group(1));
            salaryMax = lakhs(m.group(2));
        } else {
            if ((m = SALARY_MIN.matcher(lower)).find()) salaryMin = lakhs(m.group(1));
            if ((m = SALARY_MAX.matcher(lower)).find()) salaryMax = lakhs(m.group(1));
            if (salaryMin == null && salaryMax == null && (m = SALARY_BARE.matcher(lower)).find()) {
                salaryMin = lakhs(m.group(1));
            }
        }

        Integer expMin = null;
        Integer expMax = null;
        if ((m = EXP_RANGE.matcher(lower)).find()) {
            expMin = Integer.parseInt(m.group(1));
            expMax = Integer.parseInt(m.group(2));
        } else if ((m = EXP_LESS.matcher(lower)).find()) {
            expMax = Math.max(0, Integer.parseInt(m.group(1)) - 1);
        } else if ((m = EXP_ATMOST.matcher(lower)).find()) {
            expMax = Integer.parseInt(m.group(1));
        } else if ((m = EXP_MORE.matcher(lower)).find()) {
            expMin = Integer.parseInt(m.group(1));
        } else if ((m = EXP_PLUS.matcher(lower)).find()) {
            expMin = Integer.parseInt(m.group(1));
        } else if ((m = EXP_EXACT.matcher(lower)).find()) {
            expMin = Integer.parseInt(m.group(1));
            expMax = expMin;
        } else if (lower.matches(".*\\b(fresher|freshers|entry[- ]level|graduate|no experience)\\b.*")) {
            expMin = 0;
            expMax = 1;
        }

        String employmentType = null;
        if (lower.matches(".*\\b(internship|intern|interns)\\b.*")) employmentType = "INTERNSHIP";
        else if (lower.matches(".*\\b(part[- ]time)\\b.*")) employmentType = "PART_TIME";
        else if (lower.matches(".*\\b(contract|contractual|freelance)\\b.*")) employmentType = "CONTRACT";
        else if (lower.matches(".*\\b(full[- ]time|permanent)\\b.*")) employmentType = "FULL_TIME";

        List<String> keywords = extractKeywords(text, skills, location);
        JobSearchCriteria criteria = new JobSearchCriteria(keywords, skills, location, remote, expMin, expMax,
                salaryMin, salaryMax, employmentType);

        List<Integer> ordinals = extractOrdinals(lower);
        List<Long> jobIds = extractJobIds(lower);
        ChatIntent intent = classify(lower, criteria, ordinals, jobIds, hasPreviousResults);
        if (intent == ChatIntent.COMPARE && ordinals.isEmpty() && jobIds.isEmpty()) {
            ordinals = smallNumbers(lower);
        }
        return new QueryAnalysis(intent, criteria, text, ordinals, jobIds, "rules", List.of());
    }

    ChatIntent classify(String lower, JobSearchCriteria criteria, List<Integer> ordinals, List<Long> jobIds,
                        boolean hasPreviousResults) {
        if (COMPARE.matcher(lower).find()) return ChatIntent.COMPARE;
        boolean refersToJob = JOB_REFERENCE.matcher(lower).find() || !jobIds.isEmpty()
                || (!ordinals.isEmpty() && lower.matches(".*\\b(job|role|one|position)\\b.*"));
        if (refersToJob && QUESTION.matcher(lower).find() && !criteria.hasStructuredFilters()) {
            return ChatIntent.JOB_QUESTION;
        }
        if (hasPreviousResults && REFINE.matcher(lower).find()) return ChatIntent.REFINE;
        boolean shortGreeting = GREETING.matcher(lower).matches() && lower.split("\\s+").length <= 4;
        if (shortGreeting && criteria.skills().isEmpty() && !criteria.hasStructuredFilters()) return ChatIntent.GENERAL;
        // Only treat the message as a search when it is about jobs: it names a skill, a filter,
        // or uses job vocabulary. Everything else ("helo", "how are you?") is small talk.
        if (criteria.skills().isEmpty() && !criteria.hasStructuredFilters() && !JOB_VOCABULARY.matcher(lower).find()) {
            return ChatIntent.GENERAL;
        }
        return ChatIntent.NEW_SEARCH;
    }

    /** Normalise keywords proposed by the LLM with the same filtering as rule-extracted ones. */
    public List<String> cleanKeywords(List<String> keywords, List<String> skills, String location) {
        return extractKeywords(String.join(" ", keywords), skills, location);
    }

    private List<String> extractKeywords(String text, List<String> skills, String location) {
        Set<String> skillTokens = new LinkedHashSet<>();
        for (String s : skills) skillTokens.addAll(LocalHashingEmbeddingService.tokenize(s));
        Set<String> locationTokens = location == null ? Set.of()
                : new LinkedHashSet<>(LocalHashingEmbeddingService.tokenize(location));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String tok : LocalHashingEmbeddingService.tokenize(text)) {
            if (tok.length() < 2 || tok.chars().allMatch(Character::isDigit)) continue;
            if (NON_TOPIC.contains(tok) || skillTokens.contains(tok) || locationTokens.contains(tok)) continue;
            if (locationNormalizer.detect(tok).isPresent()) continue;
            out.add(tok);
            if (out.size() >= 8) break;
        }
        return new ArrayList<>(out);
    }

    private List<Integer> extractOrdinals(String lower) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        Matcher m = FIRST_N.matcher(lower);
        if (m.find()) {
            int n = NUMBER_WORDS.get(m.group(1));
            for (int i = 1; i <= n; i++) out.add(i);
            return new ArrayList<>(out);
        }
        m = ORDINAL_WORD.matcher(lower);
        while (m.find()) out.add(ORDINALS.get(m.group(1)));
        if (lower.matches(".*\\b(last) (job|one|role)\\b.*")) out.add(-1);
        return new ArrayList<>(out);
    }

    private List<Long> extractJobIds(String lower) {
        List<Long> out = new ArrayList<>();
        Matcher m = EXPLICIT_ID.matcher(lower);
        while (m.find()) out.add(Long.parseLong(m.group(1)));
        return out;
    }

    private List<Integer> smallNumbers(String lower) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        Matcher m = SMALL_NUMBER.matcher(lower);
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out.size() >= 2 ? new ArrayList<>(out) : List.of();
    }

    private static Long lakhs(String number) {
        return Math.round(Double.parseDouble(number) * 100_000);
    }
}
