package com.jobassistant.dto;

/** A backend tool the chatbot called during a turn (shown in the UI and the RAG trace). */
public record ToolCallRecord(String name, String arguments, String summary, long durationMs, boolean ok) {
}
