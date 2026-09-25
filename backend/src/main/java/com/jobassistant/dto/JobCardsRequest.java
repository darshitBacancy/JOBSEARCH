package com.jobassistant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record JobCardsRequest(
        @NotNull(message = "jobIds is required")
        @Size(max = 50, message = "at most 50 job ids per request")
        List<Long> jobIds) {
}
