package com.jobassistant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Explicit filters chosen in the UI. They override anything extracted from the message. */
public record SearchFilters(
        Boolean remote,
        @Size(max = 100) String location,
        @Min(0) @Max(1_000_000_000L) Long salaryMin,
        @Min(0) @Max(50) Integer experienceMin,
        @Min(0) @Max(50) Integer experienceMax,
        @Size(max = 20) String employmentType) {

    public JobSearchCriteria toCriteria() {
        return new JobSearchCriteria(null, null, location, Boolean.TRUE.equals(remote) ? Boolean.TRUE : null,
                experienceMin, experienceMax, salaryMin, null, employmentType);
    }
}
