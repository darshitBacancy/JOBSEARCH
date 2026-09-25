package com.jobassistant.ai;

import java.util.List;

/** A model turn: either final text, or tool calls to execute (possibly with some text). */
public record AiReply(String content, List<ToolCall> toolCalls, String finishReason) {

    public AiReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
