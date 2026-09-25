package com.jobassistant.ai;

import java.util.List;

/**
 * Chat-completion abstraction. The rest of the application depends only on this interface,
 * so the provider (OpenRouter today) or model can be swapped without touching business logic.
 */
public interface AiService {

    /**
     * @return the assistant's reply text
     * @throws AiUnavailableException when the provider is not configured or the call fails
     */
    String chat(List<AiMessage> messages, ChatOptions options);

    default String chat(List<AiMessage> messages) {
        return chat(messages, ChatOptions.defaults());
    }

    /**
     * One model turn with tools available. The model either answers or asks for tool calls,
     * which the caller executes and sends back as {@code tool} messages.
     *
     * @throws AiUnavailableException when the provider is not configured or the call fails
     */
    default AiReply chatWithTools(List<AiMessage> messages, List<ToolDefinition> tools, ChatOptions options) {
        throw new AiUnavailableException("Tool calling is not supported by this AI provider");
    }

    /** True when the provider has the configuration it needs (e.g. an API key). */
    boolean isConfigured();

    String modelName();

    /** Health based on the most recent call: ONLINE, UNAVAILABLE, NOT_CONFIGURED or UNKNOWN (no call yet). */
    default Health health() {
        return new Health(isConfigured() ? "UNKNOWN" : "NOT_CONFIGURED", null);
    }

    record Health(String status, String lastError) {
    }

    record ChatOptions(Double temperature, Integer maxTokens) {
        public static ChatOptions defaults() {
            return new ChatOptions(null, null);
        }

        public static ChatOptions precise(int maxTokens) {
            return new ChatOptions(0.0, maxTokens);
        }
    }
}
