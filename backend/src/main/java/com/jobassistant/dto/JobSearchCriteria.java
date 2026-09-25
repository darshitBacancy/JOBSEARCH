package com.jobassistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Structured requirements extracted from a natural-language query (by the LLM, with a
 * deterministic rule-based fallback). Salary values are annual amounts in INR
 * (10 LPA = 1,000,000). Null means "not specified".
 */
public record JobSearchCriteria(
        @Size(max = 20) List<String> keywords,
        @Size(max = 20) List<String> skills,
        @Size(max = 100) String location,
        Boolean remote,
        @Min(0) @Max(50) Integer experienceMin,
        @Min(0) @Max(50) Integer experienceMax,
        @Min(0) @Max(1_000_000_000L) Long salaryMin,
        @Min(0) @Max(1_000_000_000L) Long salaryMax,
        String employmentType) {

    public JobSearchCriteria {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        skills = skills == null ? List.of() : List.copyOf(skills);
        location = blankToNull(location);
        employmentType = blankToNull(employmentType);
    }

    public static JobSearchCriteria empty() {
        return new JobSearchCriteria(null, null, null, null, null, null, null, null, null);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return keywords.isEmpty() && skills.isEmpty() && !hasStructuredFilters();
    }

    public boolean hasStructuredFilters() {
        return location != null || remote != null || experienceMin != null || experienceMax != null
                || salaryMin != null || salaryMax != null || employmentType != null;
    }

    public boolean hasTopic() {
        return !skills.isEmpty() || !keywords.isEmpty();
    }

    /**
     * Combine with a follow-up: scalar values from {@code override} win when present,
     * skill and keyword lists are unioned ("only those with AWS" adds AWS).
     */
    public JobSearchCriteria mergedWith(JobSearchCriteria override) {
        if (override == null) return this;
        return new JobSearchCriteria(
                union(keywords, override.keywords),
                union(skills, override.skills),
                override.location != null ? override.location : location,
                override.remote != null ? override.remote : remote,
                override.experienceMin != null || override.experienceMax != null ? override.experienceMin : experienceMin,
                override.experienceMin != null || override.experienceMax != null ? override.experienceMax : experienceMax,
                override.salaryMin != null ? override.salaryMin : salaryMin,
                override.salaryMax != null ? override.salaryMax : salaryMax,
                override.employmentType != null ? override.employmentType : employmentType);
    }

    public JobSearchCriteria withSkills(List<String> newSkills, List<String> newKeywords) {
        return new JobSearchCriteria(newKeywords, newSkills, location, remote, experienceMin, experienceMax,
                salaryMin, salaryMax, employmentType);
    }

    public JobSearchCriteria withoutSalary() {
        return new JobSearchCriteria(keywords, skills, location, remote, experienceMin, experienceMax, null, null, employmentType);
    }

    public JobSearchCriteria withoutExperience() {
        return new JobSearchCriteria(keywords, skills, location, remote, null, null, salaryMin, salaryMax, employmentType);
    }

    public JobSearchCriteria withoutLocation() {
        return new JobSearchCriteria(keywords, skills, null, remote, experienceMin, experienceMax, salaryMin, salaryMax, employmentType);
    }

    public JobSearchCriteria withoutEmploymentType() {
        return new JobSearchCriteria(keywords, skills, location, remote, experienceMin, experienceMax, salaryMin, salaryMax, null);
    }

    public JobSearchCriteria withoutRemote() {
        return new JobSearchCriteria(keywords, skills, location, null, experienceMin, experienceMax, salaryMin, salaryMax, employmentType);
    }

    private static List<String> union(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>(a);
        set.addAll(b);
        return new ArrayList<>(set);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
