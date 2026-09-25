package com.jobassistant.service;

import com.jobassistant.dto.JobSearchCriteria;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.search.RankedJob;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic answers built only from database facts. Used when the LLM is unavailable, so
 * the assistant stays useful (and still cannot hallucinate).
 */
@Component
public class FallbackAnswerBuilder {

    public String searchAnswer(List<RankedJob> top, int total, JobSearchCriteria c, List<String> relaxed) {
        StringBuilder sb = new StringBuilder();
        String filters = describe(c);
        if (relaxed != null && !relaxed.isEmpty()) {
            sb.append("No job matched all of your filters, so I relaxed: ").append(String.join(", ", relaxed))
                    .append(". Here are the closest matches");
        } else {
            sb.append("I found ").append(total).append(total == 1 ? " job" : " jobs").append(" matching your request");
        }
        if (!filters.isEmpty()) sb.append(" (").append(filters).append(")");
        sb.append(total > top.size() ? ". Top " + top.size() + ":" : ":").append("\n\n");
        for (int i = 0; i < top.size(); i++) {
            Job j = top.get(i).job();
            sb.append(i + 1).append(". **").append(j.getTitle()).append("** - ").append(j.getCompany())
                    .append(" (Job #").append(j.getId()).append(")\n   ")
                    .append(j.getLocation()).append(j.isRemote() ? " · Remote" : "").append(" · ")
                    .append(JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency())).append(" · ")
                    .append(JobMapper.formatExperience(j.getExperienceMin(), j.getExperienceMax())).append('\n');
            List<String> reasons = top.get(i).reasons();
            if (!reasons.isEmpty()) {
                sb.append("   Why: ").append(String.join("; ", reasons.subList(0, Math.min(3, reasons.size())))).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Offline reply to small talk, used only when the LLM cannot be reached. Friendly, but it never
     * pretends to be a generated answer: it says what still works.
     */
    public String smallTalk(String message) {
        String q = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        String help = "I can search the job listings for you - tell me a role, skills, location, experience or "
                + "salary, e.g. \"remote Java jobs above 12 LPA\".";
        if (q.matches("^(thanks|thank you|thx|ty|great|cool|ok|okay)\\b.*")) {
            return "You're welcome! " + help;
        }
        if (q.matches("^(bye|goodbye|see you|good night)\\b.*")) {
            return "Goodbye, and good luck with your job search!";
        }
        if (q.matches("^(hi|hello|hey|helo|hii+|hola|namaste|good (morning|afternoon|evening))\\b.*")) {
            return "Hi there! 👋 I'm your Job Search Assistant. " + help;
        }
        return "I'm your Job Search Assistant. My AI chat is temporarily unavailable, so I can't answer general "
                + "questions right now. " + help;
    }

    public String noResults(JobSearchCriteria c) {
        String filters = describe(c);
        return "I couldn't find any jobs in the knowledge base matching your request"
                + (filters.isEmpty() ? "" : " (" + filters + ")")
                + ". Try removing a filter, widening the salary or experience range, or searching for a related skill.";
    }

    /** Answers a question about one job from its structured fields. */
    public String jobAnswer(Job j, String question) {
        String q = question.toLowerCase(Locale.ROOT);
        String ref = "Job #" + j.getId() + " (" + j.getTitle() + " at " + j.getCompany() + ")";
        List<String> parts = new ArrayList<>();
        if (q.contains("skill") || q.contains("tech") || q.contains("stack")) {
            parts.add(ref + " lists these skills: " + String.join(", ", j.getSkills()) + ".");
        }
        if (q.contains("require") || q.contains("qualif") || q.contains("eligib")) {
            parts.add("Requirements:\n" + bullets(j.getRequirements()));
        }
        if (q.contains("responsib") || q.contains("duties") || q.contains("day to day") || q.contains("role involve")) {
            parts.add("Responsibilities:\n" + bullets(j.getResponsibilities()));
        }
        if (q.contains("benefit") || q.contains("perk") || q.contains("insurance")) {
            parts.add("Benefits:\n" + bullets(j.getBenefits()));
        }
        if (q.contains("salary") || q.contains("pay") || q.contains("ctc") || q.contains("compensation")) {
            parts.add("Salary: " + JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency()) + ".");
        }
        if (q.contains("experience") || q.contains("years")) {
            parts.add("Experience required: " + JobMapper.formatExperience(j.getExperienceMin(), j.getExperienceMax()) + ".");
        }
        if (q.contains("remote") || q.contains("location") || q.contains("where") || q.contains("office")) {
            parts.add("Location: " + j.getLocation() + (j.isRemote() ? " (remote-friendly)." : " (not remote)."));
        }
        if (q.contains("apply") || q.contains("link") || q.contains("url")) {
            parts.add("Application link: " + j.getApplicationUrl());
        }
        if (parts.isEmpty()) {
            parts.add(ref + ": " + j.getDescription());
            parts.add("Key facts: " + j.getLocation() + (j.isRemote() ? " (remote)" : "") + ", "
                    + JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency()) + ", "
                    + JobMapper.formatExperience(j.getExperienceMin(), j.getExperienceMax()) + ", skills: "
                    + String.join(", ", j.getSkills()) + ".");
        } else if (!parts.get(0).startsWith(ref)) {
            parts.add(0, "Here is what the listing for " + ref + " says:");
        }
        return String.join("\n\n", parts);
    }

    public String describe(JobSearchCriteria c) {
        if (c == null) return "";
        List<String> parts = new ArrayList<>();
        if (!c.skills().isEmpty()) parts.add(String.join(", ", c.skills()));
        else if (!c.keywords().isEmpty()) parts.add(String.join(", ", c.keywords()));
        if (Boolean.TRUE.equals(c.remote())) parts.add("remote");
        if (Boolean.FALSE.equals(c.remote())) parts.add("on-site");
        if (c.location() != null) parts.add(c.location());
        if (c.salaryMin() != null) parts.add("≥ ₹" + JobMapper.lakhs(c.salaryMin()) + " LPA");
        if (c.salaryMax() != null) parts.add("≤ ₹" + JobMapper.lakhs(c.salaryMax()) + " LPA");
        if (c.experienceMin() != null && c.experienceMin().equals(c.experienceMax())) parts.add(c.experienceMin() + " yrs experience");
        else if (c.experienceMin() != null && c.experienceMax() != null) parts.add(c.experienceMin() + "-" + c.experienceMax() + " yrs experience");
        else if (c.experienceMax() != null) parts.add("≤ " + c.experienceMax() + " yrs experience");
        else if (c.experienceMin() != null) parts.add(c.experienceMin() + "+ yrs experience");
        if (c.employmentType() != null) parts.add(JobMapper.employmentTypeLabel(c.employmentType()));
        return String.join(" · ", parts);
    }

    private static String bullets(List<String> items) {
        return items.isEmpty() ? "- Not specified in the listing" : "- " + String.join("\n- ", items);
    }
}
