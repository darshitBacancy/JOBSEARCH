package com.jobassistant.dto;

import java.util.List;

/** A job whose retrieved content was supplied to the LLM as grounding context. */
public record SourceRef(Long jobId, String title, String company, List<String> sections, Double similarity) {
}
