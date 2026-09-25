package com.jobassistant.dto;

import java.util.List;

public record ChatMessageDto(Long id, String role, String message, List<Long> jobIds, String timestamp,
                             String feedback) {
}
