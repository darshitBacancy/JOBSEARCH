package com.jobassistant.dto;

import java.util.List;
import java.util.Map;

public record JobDetailDto(
        Long id,
        String title,
        String company,
        String location,
        boolean remote,
        String employmentType,
        String employmentTypeLabel,
        String experience,
        int experienceMin,
        int experienceMax,
        String salary,
        long salaryMin,
        long salaryMax,
        String currency,
        List<String> skills,
        String postedDate,
        Integer matchScore,
        String matchLabel,
        List<String> matchReasons,
        List<String> gaps,
        Map<String, Double> scoreBreakdown,
        String description,
        List<String> requirements,
        List<String> responsibilities,
        List<String> benefits,
        String applicationUrl,
        String dataNotice) {
}
