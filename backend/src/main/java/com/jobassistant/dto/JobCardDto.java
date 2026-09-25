package com.jobassistant.dto;

import java.util.List;
import java.util.Map;

/** Compact job representation rendered as a card. Match fields are null outside of a search. */
public record JobCardDto(
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
        Map<String, Double> scoreBreakdown) {
}
