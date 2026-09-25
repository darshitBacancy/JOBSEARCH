package com.jobassistant.search;

import com.jobassistant.config.RagProperties;
import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.rag.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Transparent, configurable relevance scoring.
 * <pre>
 *   score = sum(weight_i * component_i) / sum(weight_i)   over the components that apply
 * </pre>
 * Components: semantic similarity (relative to the best retrieved job), skill overlap,
 * experience fit, location, remote preference and salary. A component the user did not ask
 * about is "not applicable" (null) and does not dilute the score.
 */
@Component
public class RelevanceScorer {

    private final RagProperties.Ranking weights;
    private final LocationNormalizer locations;

    public RelevanceScorer(RagProperties props, LocationNormalizer locations) {
        this.weights = props.ranking();
        this.locations = locations;
    }

    /**
     * @param semanticNorm semantic similarity normalised to 0..1, or null when semantic search does not apply
     */
    public RankedJob score(Job job, JobSearchCriteria c, Double semanticNorm, double rawSimilarity,
                           List<ScoredChunk> chunks) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        double weighted = 0;
        double totalWeight = 0;

        // --- semantic
        if (semanticNorm != null) {
            breakdown.put("semantic", round(semanticNorm));
            weighted += weights.semantic() * semanticNorm;
            totalWeight += weights.semantic();
            if (semanticNorm >= 0.8 && !chunks.isEmpty()) {
                reasons.add("Job content closely matches your request (" + sectionsOf(chunks) + ")");
            }
        } else {
            breakdown.put("semantic", null);
        }

        // --- skills
        if (!c.skills().isEmpty()) {
            Set<String> jobSkills = job.getSkills().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            List<String> matched = c.skills().stream().filter(s -> jobSkills.contains(s.toLowerCase(Locale.ROOT))).toList();
            List<String> missing = c.skills().stream().filter(s -> !jobSkills.contains(s.toLowerCase(Locale.ROOT))).toList();
            double v = (double) matched.size() / c.skills().size();
            breakdown.put("skills", round(v));
            weighted += weights.skills() * v;
            totalWeight += weights.skills();
            if (!matched.isEmpty()) reasons.add(joinHuman(matched) + (matched.size() == 1 ? " matches" : " match"));
            if (!missing.isEmpty()) gaps.add(joinHuman(missing) + " not listed in the job's skills");
        } else {
            breakdown.put("skills", null);
        }

        // --- experience
        if (c.experienceMin() != null || c.experienceMax() != null) {
            double v = experienceFit(job, c);
            breakdown.put("experience", round(v));
            weighted += weights.experience() * v;
            totalWeight += weights.experience();
            String required = JobMapper.formatExperience(job.getExperienceMin(), job.getExperienceMax());
            if (v >= 1.0) reasons.add("Requires " + required + " - within your range (" + describeExperience(c) + ")");
            else gaps.add("Requires " + required + " (you asked for " + describeExperience(c) + ")");
        } else {
            breakdown.put("experience", null);
        }

        // --- location
        if (c.location() != null) {
            double v;
            if (locations.matches(job.getLocation(), c.location())) {
                v = 1.0;
                reasons.add("Located in " + job.getLocation());
            } else if (job.isRemote() && Boolean.TRUE.equals(c.remote())) {
                v = 0.8;
                reasons.add("Remote role (listed location: " + job.getLocation() + ")");
            } else {
                v = 0.0;
                gaps.add("Located in " + job.getLocation() + ", not " + c.location());
            }
            breakdown.put("location", round(v));
            weighted += weights.location() * v;
            totalWeight += weights.location();
        } else {
            breakdown.put("location", null);
        }

        // --- remote
        if (c.remote() != null) {
            boolean ok = job.isRemote() == c.remote();
            double v = ok ? 1.0 : 0.0;
            breakdown.put("remote", v);
            weighted += weights.remote() * v;
            totalWeight += weights.remote();
            if (ok) reasons.add(c.remote() ? "Remote preference matches" : "On-site role as requested");
            else gaps.add(job.isRemote() ? "Role is remote, you asked for on-site" : "Role is not remote");
        } else {
            breakdown.put("remote", null);
        }

        // --- salary
        if (c.salaryMin() != null || c.salaryMax() != null) {
            double v = salaryFit(job, c, reasons, gaps);
            breakdown.put("salary", round(v));
            weighted += weights.salary() * v;
            totalWeight += weights.salary();
        } else {
            breakdown.put("salary", null);
        }

        if (c.employmentType() != null && c.employmentType().equalsIgnoreCase(job.getEmploymentType())) {
            reasons.add(JobMapper.employmentTypeLabel(job.getEmploymentType()) + " as requested");
        }

        double score = totalWeight == 0 ? 0.0 : weighted / totalWeight;
        return new RankedJob(job, score, breakdown, reasons, gaps, rawSimilarity, chunks);
    }

    private double experienceFit(Job job, JobSearchCriteria c) {
        int userMin = c.experienceMin() != null ? c.experienceMin() : 0;
        int userMax = c.experienceMax() != null ? c.experienceMax() : 60;
        if (job.getExperienceMin() <= userMax && job.getExperienceMax() >= userMin) return 1.0;
        int distance = job.getExperienceMin() > userMax ? job.getExperienceMin() - userMax : userMin - job.getExperienceMax();
        return Math.max(0.0, 1.0 - 0.25 * distance);
    }

    private double salaryFit(Job job, JobSearchCriteria c, List<String> reasons, List<String> gaps) {
        String range = JobMapper.formatSalary(job.getSalaryMin(), job.getSalaryMax(), job.getCurrency());
        double v = 1.0;
        if (c.salaryMin() != null) {
            String wanted = "₹" + JobMapper.lakhs(c.salaryMin()) + " LPA";
            if (job.getSalaryMin() >= c.salaryMin()) {
                reasons.add("Salary " + range + " meets your " + wanted + " minimum");
            } else if (job.getSalaryMax() >= c.salaryMin()) {
                v = 0.5;
                reasons.add("Upper end of " + range + " reaches your " + wanted + " minimum");
            } else {
                v = 0.0;
                gaps.add("Salary " + range + " is below your " + wanted + " minimum");
            }
        }
        if (c.salaryMax() != null) {
            String cap = "₹" + JobMapper.lakhs(c.salaryMax()) + " LPA";
            if (job.getSalaryMin() > c.salaryMax()) {
                v = 0.0;
                gaps.add("Salary " + range + " starts above " + cap);
            } else if (c.salaryMin() == null) {
                reasons.add("Salary " + range + " is within " + cap);
            }
        }
        return v;
    }

    private static String describeExperience(JobSearchCriteria c) {
        Integer min = c.experienceMin();
        Integer max = c.experienceMax();
        if (min != null && min.equals(max)) return min + " years of experience";
        if (min != null && max != null) return min + "-" + max + " years";
        if (max != null) return "up to " + max + " years";
        return min + "+ years";
    }

    private static String sectionsOf(List<ScoredChunk> chunks) {
        return chunks.stream().map(ch -> ch.record().section().toLowerCase(Locale.ROOT)).distinct().limit(3)
                .collect(Collectors.joining(", "));
    }

    static String joinHuman(List<String> items) {
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
