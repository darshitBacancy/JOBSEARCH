package com.jobassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegenerateRequest(
        @NotBlank(message = "conversationId is required") @Size(max = 36) String conversationId,
        boolean debug) {
}
