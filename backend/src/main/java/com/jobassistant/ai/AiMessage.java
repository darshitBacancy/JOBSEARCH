package com.jobassistant.ai;

import java.util.List;

/**
 * A provider-neutral chat message.
 * <ul>
 *   <li>{@code system | user | assistant} messages carry text</li>
 *   <li>an {@code assistant} message may instead carry {@link ToolCall}s requested by the model</li>
 *   <li>a {@code tool} message carries the result of one tool call ({@code toolCallId})</li>
 * </ul>
 */
public record AiMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public AiMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public AiMessage(String role, String content) {
        this(role, content, List.of(), null);
    }

    public static AiMessage system(String content) {
        return new AiMessage("system", content);
    }

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }

    public static AiMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new AiMessage("assistant", content, toolCalls, null);
    }

    public static AiMessage toolResult(String toolCallId, String content) {
        return new AiMessage("tool", content, List.of(), toolCallId);
    }
}
