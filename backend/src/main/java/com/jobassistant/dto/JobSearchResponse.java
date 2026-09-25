package com.jobassistant.dto;

import java.util.List;

public record JobSearchResponse(
        List<JobCardDto> jobs,
        int totalMatches,
        JobSearchCriteria criteria,
        List<String> relaxedFilters,
        String retrievalMode) {
}
