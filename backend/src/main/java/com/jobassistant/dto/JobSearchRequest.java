package com.jobassistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Deterministic hybrid search (no LLM involved): optional free text plus explicit filters. */
public record JobSearchRequest(
        @Size(max = 2000) String query,
        @Size(max = 20) List<String> skills,
        @Size(max = 100) String location,
        Boolean remote,
        @Min(0) @Max(50) Integer experienceMin,
        @Min(0) @Max(50) Integer experienceMax,
        @Min(0) Long salaryMin,
        String employmentType,
        @Min(1) @Max(50) Integer limit) {
}
