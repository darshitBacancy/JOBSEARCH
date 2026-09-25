package com.jobassistant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CompareRequest(
        @NotNull(message = "jobIds is required")
        @Size(min = 2, max = 4, message = "select between 2 and 4 jobs to compare")
        List<Long> jobIds) {
}
