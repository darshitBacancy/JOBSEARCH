package com.jobassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.config.OpenRouterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls an OpenAI-compatible {@code POST /chat/completions} endpoint (Google Gemini by default,
 * OpenRouter also works), with optional function/tool calling.
 * <p>
 * Transient failures (429 / 5xx / network) are retried once and then the next model in
 * {@code openrouter.fallback-chat-models} is tried, because free-tier Gemini models are often
 * briefly overloaded.
 */
@Service
public class OpenRouterAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAiService.class);
    private static final int ATTEMPTS_PER_MODEL = 2;

    private final RestClient restClient;
    private final OpenRouterProperties props;
    private final ObjectMapper mapper;
    private volatile Health lastHealth = null;
    private volatile String lastModel;

    public OpenRouterAiService(RestClient openRouterRestClient, OpenRouterProperties props, ObjectMapper mapper) {
        this.restClient = openRouterRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String chat(List<AiMessage> messages, ChatOptions options) {
        AiReply reply = complete(messages, List.of(), options);
        if (reply.content() == null || reply.content().isBlank()) {
            throw new AiUnavailableException("LLM response did not contain any message content (finish_reason="
                    + reply.finishReason() + ("length".equals(reply.finishReason())
                    ? ", token budget exhausted - raise openrouter.max-tokens" : "") + ")");
        }
        return reply.content();
    }

    @Override
    public AiReply chatWithTools(List<AiMessage> messages, List<ToolDefinition> tools, ChatOptions options) {
        AiReply reply = complete(messages, tools, options);
        if (!reply.hasToolCalls() && (reply.content() == null || reply.content().isBlank())) {
            throw new AiUnavailableException("LLM response contained neither content nor tool calls (finish_reason="
                    + reply.finishReason() + ")");
        }
        return reply;
    }

    private AiReply complete(List<AiMessage> messages, List<ToolDefinition> tools, ChatOptions options) {
        if (!isConfigured()) {
            throw new AiUnavailableException("LLM API key is not configured (set GEMINI_API_KEY)");
        }
        AiUnavailableException last = null;
        for (String model : props.chatModelChain()) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_MODEL; attempt++) {
                try {
                    AiReply reply = completeOnce(model, messages, tools, options);
                    lastModel = model;
                    return reply;
                } catch (AiUnavailableException e) {
                    last = e;
                    if (!e.isRetryable()) throw e;
                    log.warn("LLM call to {} failed (attempt {}): {}", model, attempt, e.getMessage());
                    if (attempt < ATTEMPTS_PER_MODEL) sleep(1500L * attempt);
                }
            }
        }
        throw last;
    }

    private AiReply completeOnce(String model, List<AiMessage> messages, List<ToolDefinition> tools, ChatOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(this::toWire).toList());
        body.put("temperature", options.temperature() != null ? options.temperature() : props.temperature());
        body.put("max_tokens", options.maxTokens() != null ? options.maxTokens() : props.maxTokens());
        if (props.isOpenRouter()) {
            // OpenRouter-only field (Gemini rejects it): keep a reasoning model's trace out of the payload
            body.put("reasoning", Map.of("exclude", true));
        } else if (props.reasoningEffort() != null) {
            body.put("reasoning_effort", props.reasoningEffort());
        }
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function", Map.of(
                    "name", t.name(), "description", t.description(), "parameters", t.parameters()))).toList());
            body.put("tool_choice", "auto");
        }

        long start = System.currentTimeMillis();
        try {
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            AiReply reply = parseReply(raw);
            lastHealth = new Health("ONLINE", null);
            log.debug("LLM chat ({}) completed in {} ms (tool calls: {})", model, System.currentTimeMillis() - start,
                    reply.toolCalls().size());
            return reply;
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String detail = summariseError(e.getResponseBodyAsString());
            lastHealth = new Health("UNAVAILABLE", "HTTP " + code + ": " + detail);
            throw new AiUnavailableException("LLM chat request (" + model + ") failed with HTTP " + code + ": " + detail,
                    e, AiUnavailableException.isRetryableStatus(code));
        } catch (AiUnavailableException e) {
            lastHealth = new Health("UNAVAILABLE", e.getMessage());
            throw e;
        } catch (Exception e) {
            lastHealth = new Health("UNAVAILABLE", e.getClass().getSimpleName());
            throw new AiUnavailableException("LLM chat request failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " - " + e.getMessage() : ""), e);
        }
    }

    private Map<String, Object> toWire(AiMessage m) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("role", m.role());
        w.put("content", m.content() == null ? "" : m.content());
        if (!m.toolCalls().isEmpty()) {
            w.put("tool_calls", m.toolCalls().stream().map(this::toWire).toList());
        }
        if (m.toolCallId() != null) w.put("tool_call_id", m.toolCallId());
        return w;
    }

    private Map<String, Object> toWire(ToolCall tc) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("id", tc.id());
        w.put("type", "function");
        w.put("function", Map.of("name", tc.name(), "arguments", tc.arguments()));
        if (tc.extraContent() != null) {
            try {
                // Gemini requires the thought_signature it sent to come back with the call
                w.put("extra_content", mapper.readTree(tc.extraContent()));
            } catch (Exception e) {
                log.debug("Dropping unparseable extra_content on tool call {}", tc.id());
            }
        }
        return w;
    }

    AiReply parseReply(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiUnavailableException("LLM returned an empty response");
        }
        try {
            JsonNode root = mapper.readTree(raw);
            // Gemini wraps errors in a one-element array
            if (root.isArray() && !root.isEmpty()) root = root.get(0);
            if (root.has("error")) {
                throw new AiUnavailableException("LLM error: " + root.path("error").path("message").asText("unknown"));
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiUnavailableException("LLM response did not contain any choices");
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            JsonNode content = message.path("content");
            String text = content.isMissingNode() || content.isNull() ? null : content.asText().trim();
            List<ToolCall> calls = new ArrayList<>();
            int n = 0;
            for (JsonNode tc : message.path("tool_calls")) {
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                JsonNode args = fn.path("arguments");
                String argsJson = args.isTextual() ? args.asText() : args.isMissingNode() || args.isNull() ? "{}" : args.toString();
                String id = tc.path("id").asText("call_" + (n++));
                JsonNode extra = tc.path("extra_content");
                calls.add(new ToolCall(id, name, argsJson.isBlank() ? "{}" : argsJson,
                        extra.isObject() ? extra.toString() : null));
            }
            return new AiReply(text, calls, choice.path("finish_reason").asText("unknown"));
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("Could not parse LLM response", e);
        }
    }

    private String summariseError(String body) {
        if (body == null || body.isBlank()) return "no details";
        try {
            JsonNode node = mapper.readTree(body);
            if (node.isArray() && !node.isEmpty()) node = node.get(0);
            String msg = node.path("error").path("message").asText(null);
            if (msg != null) return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
        } catch (Exception ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Health health() {
        if (!isConfigured()) return new Health("NOT_CONFIGURED", null);
        return lastHealth != null ? lastHealth : new Health("UNKNOWN", null);
    }

    @Override
    public boolean isConfigured() {
        return props.hasApiKey();
    }

    @Override
    public String modelName() {
        return lastModel != null ? lastModel : props.chatModel();
    }
}
