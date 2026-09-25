package com.jobassistant.dto;

/** One row of the conversation sidebar. */
public record ConversationSummaryDto(String id, String title, String createdAt, String updatedAt, long messageCount) {
}
