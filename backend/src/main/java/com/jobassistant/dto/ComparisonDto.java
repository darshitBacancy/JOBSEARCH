package com.jobassistant.dto;

import java.util.List;

public record ComparisonDto(List<JobCardDto> jobs, List<Row> rows, String summary) {

    public record Row(String label, List<String> values) {
    }
}
