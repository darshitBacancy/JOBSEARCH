package com.jobassistant.rag;

import com.jobassistant.config.RagProperties;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a job into a searchable document and splits it into section chunks
 * (overview, skills, requirements, responsibilities, benefits).
 * <p>
 * Every chunk starts with a short header naming the job, so that each vector carries
 * enough context on its own ("contextual chunk headers"), and every chunk keeps metadata
 * linking it back to the original job.
 */
@Component
public class JobDocumentBuilder {

    /** Bump when the chunking strategy changes; it is part of the dataset hash so the index is rebuilt. */
    public static final String CHUNKING_VERSION = "sections-v1";

    public enum Section { OVERVIEW, SKILLS, REQUIREMENTS, RESPONSIBILITIES, BENEFITS }

    public record Chunk(String documentId, long jobId, Section section, int index, String text,
                        Map<String, String> metadata) {
    }

    private final int maxChars;

    public JobDocumentBuilder(RagProperties props) {
        this.maxChars = props.chunkMaxChars();
    }

    /** The full job document (used for display/debugging and as the conceptual source of the chunks). */
    public String toDocument(Job job) {
        return """
                Job Title: %s

                Company: %s

                Location: %s

                Remote: %s

                Employment Type: %s

                Experience: %s

                Salary: %s

                Skills:
                %s

                Description:
                %s

                Requirements:
                %s

                Responsibilities:
                %s

                Benefits:
                %s
                """.formatted(job.getTitle(), job.getCompany(), job.getLocation(), job.isRemote() ? "Yes" : "No",
                JobMapper.employmentTypeLabel(job.getEmploymentType()),
                JobMapper.formatExperience(job.getExperienceMin(), job.getExperienceMax()),
                JobMapper.formatSalary(job.getSalaryMin(), job.getSalaryMax(), job.getCurrency()),
                String.join(", ", job.getSkills()), job.getDescription(), bullets(job.getRequirements()),
                bullets(job.getResponsibilities()), bullets(job.getBenefits())).trim();
    }

    public List<Chunk> chunk(Job job) {
        List<Chunk> chunks = new ArrayList<>();
        String header = "Job: %s at %s (%s%s)".formatted(job.getTitle(), job.getCompany(), job.getLocation(),
                job.isRemote() ? ", remote" : "");

        String overview = """
                Job Title: %s
                Company: %s
                Location: %s
                Remote: %s
                Employment Type: %s
                Experience: %s
                Salary: %s
                Description: %s""".formatted(job.getTitle(), job.getCompany(), job.getLocation(),
                job.isRemote() ? "Yes" : "No", JobMapper.employmentTypeLabel(job.getEmploymentType()),
                JobMapper.formatExperience(job.getExperienceMin(), job.getExperienceMax()),
                JobMapper.formatSalary(job.getSalaryMin(), job.getSalaryMax(), job.getCurrency()),
                job.getDescription());
        add(chunks, job, Section.OVERVIEW, null, overview);
        add(chunks, job, Section.SKILLS, header, "Skills: " + String.join(", ", job.getSkills()));
        add(chunks, job, Section.REQUIREMENTS, header, "Requirements:\n" + bullets(job.getRequirements()));
        add(chunks, job, Section.RESPONSIBILITIES, header, "Responsibilities:\n" + bullets(job.getResponsibilities()));
        add(chunks, job, Section.BENEFITS, header, "Benefits:\n" + bullets(job.getBenefits()));
        return chunks;
    }

    private void add(List<Chunk> chunks, Job job, Section section, String header, String body) {
        if (body == null || body.isBlank()) return;
        int budget = header == null ? maxChars : Math.max(200, maxChars - header.length() - 1);
        List<String> parts = TextChunker.split(body, budget);
        for (int i = 0; i < parts.size(); i++) {
            String text = header == null ? parts.get(i) : header + "\n" + parts.get(i);
            String id = "job-" + job.getId() + "-" + section.name().toLowerCase() + "-" + i;
            chunks.add(new Chunk(id, job.getId(), section, i, text, metadata(job, section)));
        }
    }

    private Map<String, String> metadata(Job job, Section section) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("jobId", String.valueOf(job.getId()));
        m.put("title", job.getTitle());
        m.put("company", job.getCompany());
        m.put("location", job.getLocation());
        m.put("skills", String.join(", ", job.getSkills()));
        m.put("remote", String.valueOf(job.isRemote()));
        m.put("section", section.name());
        return m;
    }

    private static String bullets(List<String> items) {
        if (items == null || items.isEmpty()) return "- Not specified";
        StringBuilder sb = new StringBuilder();
        for (String s : items) sb.append("- ").append(s).append('\n');
        return sb.toString().trim();
    }
}
