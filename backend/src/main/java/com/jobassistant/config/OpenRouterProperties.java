package com.jobassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/**
 * Settings for the OpenAI-compatible LLM provider (chat + embeddings). The prefix is still
 * {@code openrouter} for backwards compatibility, but any OpenAI-compatible endpoint works; the
 * default is Google Gemini's ({@code https://generativelanguage.googleapis.com/v1beta/openai}).
 * The API key is read from GEMINI_API_KEY (or OPENROUTER_API_KEY) and is never exposed through
 * any API response or log line.
 */
@ConfigurationProperties(prefix = "openrouter")
public record OpenRouterProperties(
        String apiKey,
        String baseUrl,
        String chatModel,
        String embeddingModel,
        Integer timeoutSeconds,
        Double temperature,
        Integer maxTokens,
        String appName,
        String siteUrl,
        List<String> fallbackChatModels,
        String reasoningEffort) {

    public static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";

    @ConstructorBinding
    public OpenRouterProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = GEMINI_BASE_URL;
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        if (chatModel == null || chatModel.isBlank()) chatModel = "gemini-flash-latest";
        if (embeddingModel == null || embeddingModel.isBlank()) embeddingModel = "gemini-embedding-001";
        if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 60;
        if (temperature == null) temperature = 0.2;
        if (maxTokens == null || maxTokens <= 0) maxTokens = 900;
        if (appName == null || appName.isBlank()) appName = "Job Search Assistant";
        if (siteUrl == null || siteUrl.isBlank()) siteUrl = "http://localhost:4000";
        fallbackChatModels = fallbackChatModels == null ? List.of()
                : fallbackChatModels.stream().filter(m -> m != null && !m.isBlank()).map(String::trim).toList();
        if (reasoningEffort != null && reasoningEffort.isBlank()) reasoningEffort = null;
    }

    public OpenRouterProperties(String apiKey, String baseUrl, String chatModel, String embeddingModel,
                                Integer timeoutSeconds, Double temperature, Integer maxTokens,
                                String appName, String siteUrl) {
        this(apiKey, baseUrl, chatModel, embeddingModel, timeoutSeconds, temperature, maxTokens, appName, siteUrl,
                null, null);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Gemini's OpenAI-compatible endpoint rejects OpenRouter-only fields such as {@code reasoning}. */
    public boolean isOpenRouter() {
        return baseUrl.contains("openrouter.ai");
    }

    /** Primary chat model followed by the fallbacks, without duplicates. */
    public List<String> chatModelChain() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(chatModel), fallbackChatModels.stream())
                .distinct().toList();
    }

    /** Never print the key, even in logs or debug output. */
    @Override
    public String toString() {
        return "OpenRouterProperties[baseUrl=" + baseUrl + ", chatModel=" + chatModel
                + ", fallbackChatModels=" + fallbackChatModels
                + ", embeddingModel=" + embeddingModel + ", apiKey=" + (hasApiKey() ? "***" : "<not set>") + "]";
    }
}
