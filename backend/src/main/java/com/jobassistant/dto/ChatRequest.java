package com.jobassistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @Size(max = 36) String conversationId,
        @NotBlank(message = "message must not be blank") @Size(max = 2000, message = "message must be at most 2000 characters") String message,
        @Size(max = 4) List<Long> selectedJobIds,
        Long focusJobId,
        @Valid SearchFilters filters,
        boolean debug) {
}
