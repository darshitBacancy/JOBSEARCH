package com.jobassistant.ai;

/**
 * A function call requested by the model.
 *
 * @param arguments    the raw JSON arguments string produced by the model
 * @param extraContent provider-specific JSON that must be echoed back with the call, or {@code null}.
 *                     Gemini puts the {@code thought_signature} here and rejects follow-up turns without it.
 */
public record ToolCall(String id, String name, String arguments, String extraContent) {

    public ToolCall(String id, String name, String arguments) {
        this(id, name, arguments, null);
    }
}
