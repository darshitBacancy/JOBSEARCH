package com.jobassistant.service;

import com.jobassistant.dto.ComparisonDto;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.rag.PromptTemplates;
import com.jobassistant.rag.RagService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Side-by-side comparison built strictly from job data, with an LLM-written (grounded) summary. */
@Service
public class ComparisonService {

    public record ComparisonResult(ComparisonDto comparison, boolean summaryFromLlm, String note, String context) {
    }

    private final RagService ragService;
    private final JobMapper mapper;

    public ComparisonService(RagService ragService, JobMapper mapper) {
        this.ragService = ragService;
        this.mapper = mapper;
    }

    /** Comparison table from DB facts with a deterministic summary (no LLM call). Used by the agent's tool. */
    public ComparisonDto table(List<Job> jobs) {
        return new ComparisonDto(jobs.stream().map(mapper::toCard).toList(), rows(jobs), deterministicSummary(jobs));
    }

    public ComparisonResult compare(List<Job> jobs) {
        List<ComparisonDto.Row> rows = rows(jobs);
        String context = ragService.buildComparisonContext(jobs);
        Set<Long> ids = jobs.stream().map(Job::getId).collect(Collectors.toSet());
        RagService.GroundedAnswer answer = ragService.generateGroundedAnswer(PromptTemplates.COMPARE_SYSTEM, context,
                "Compare these jobs: " + jobs.stream().map(j -> "Job #" + j.getId()).collect(Collectors.joining(", ")),
                List.of(), ids);
        String summary = answer.text() != null ? answer.text() : deterministicSummary(jobs);
        ComparisonDto dto = new ComparisonDto(jobs.stream().map(mapper::toCard).toList(), rows, summary);
        return new ComparisonResult(dto, answer.fromLlm(), answer.note(), context);
    }

    private static List<ComparisonDto.Row> rows(List<Job> jobs) {
        return List.of(
                row("Title", jobs, Job::getTitle),
                row("Company", jobs, Job::getCompany),
                row("Location", jobs, Job::getLocation),
                row("Remote", jobs, j -> j.isRemote() ? "Yes" : "No"),
                row("Experience", jobs, j -> JobMapper.formatExperience(j.getExperienceMin(), j.getExperienceMax())),
                row("Salary", jobs, j -> JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency())),
                row("Skills", jobs, j -> String.join(", ", j.getSkills())),
                row("Employment", jobs, j -> JobMapper.employmentTypeLabel(j.getEmploymentType())),
                row("Posted", jobs, j -> String.valueOf(j.getPostedDate())),
                row("Benefits", jobs, j -> String.join(", ", j.getBenefits())));
    }

    private static ComparisonDto.Row row(String label, List<Job> jobs, Function<Job, String> f) {
        return new ComparisonDto.Row(label, jobs.stream().map(f).toList());
    }

    /** Factual summary computed directly from the data (used when the LLM is unavailable). */
    static String deterministicSummary(List<Job> jobs) {
        StringBuilder sb = new StringBuilder();
        Job topPay = jobs.stream().max(Comparator.comparingLong(Job::getSalaryMax)).orElseThrow();
        Job entry = jobs.stream().min(Comparator.comparingInt(Job::getExperienceMin)).orElseThrow();
        sb.append("- Highest salary ceiling: Job #").append(topPay.getId()).append(" (").append(topPay.getTitle())
                .append(", ").append(JobMapper.formatSalary(topPay.getSalaryMin(), topPay.getSalaryMax(), topPay.getCurrency()))
                .append(").\n");
        sb.append("- Lowest experience requirement: Job #").append(entry.getId()).append(" (")
                .append(JobMapper.formatExperience(entry.getExperienceMin(), entry.getExperienceMax())).append(").\n");
        List<String> remote = jobs.stream().filter(Job::isRemote).map(j -> "Job #" + j.getId()).toList();
        sb.append("- Remote: ").append(remote.isEmpty() ? "none of these roles" : String.join(", ", remote)).append(".\n");
        Set<String> common = new LinkedHashSet<>(jobs.get(0).getSkills());
        for (Job j : jobs) common.retainAll(j.getSkills());
        sb.append("- Skills in common: ").append(common.isEmpty() ? "none" : String.join(", ", common)).append(".\n");
        for (Job j : jobs) {
            List<String> unique = new ArrayList<>(j.getSkills());
            for (Job other : jobs) if (other != j) unique.removeAll(other.getSkills());
            if (!unique.isEmpty()) {
                sb.append("- Only Job #").append(j.getId()).append(" lists: ").append(String.join(", ", unique)).append(".\n");
            }
        }
        return sb.toString().trim();
    }
}
