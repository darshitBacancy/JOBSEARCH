package com.jobassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.config.OpenRouterProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Calls the OpenAI-compatible (Gemini / OpenRouter) {@code POST /embeddings} endpoint with the configured
 * embedding model (not the chat model).
 */
@Service
public class OpenRouterEmbeddingService implements EmbeddingService {

    public static final String PROVIDER = "openrouter";

    private final RestClient restClient;
    private final OpenRouterProperties props;
    private final ObjectMapper mapper;

    public OpenRouterEmbeddingService(RestClient openRouterRestClient, OpenRouterProperties props, ObjectMapper mapper) {
        this.restClient = openRouterRestClient;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String model() {
        return props.embeddingModel();
    }

    @Override
    public boolean isAvailable() {
        return props.hasApiKey();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!isAvailable()) {
            throw new AiUnavailableException("LLM API key is not configured (set GEMINI_API_KEY)");
        }
        if (texts.isEmpty()) return List.of();
        try {
            String raw = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                    .body(Map.of("model", props.embeddingModel(), "input", texts))
                    .retrieve()
                    .body(String.class);
            return parse(raw, texts.size());
        } catch (RestClientResponseException e) {
            throw new AiUnavailableException("Embeddings request failed with HTTP "
                    + e.getStatusCode().value() + ": " + truncate(e.getResponseBodyAsString()), e,
                    AiUnavailableException.isRetryableStatus(e.getStatusCode().value()));
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("Embeddings request failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " - " + e.getMessage() : ""), e);
        }
    }

    List<float[]> parse(String raw, int expected) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (root.isArray() && !root.isEmpty()) root = root.get(0);
            if (root.has("error")) {
                throw new AiUnavailableException("Embeddings error: "
                        + root.path("error").path("message").asText("unknown"));
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expected) {
                throw new AiUnavailableException("Embeddings endpoint returned " + (data.isArray() ? data.size() : 0)
                        + " embeddings, expected " + expected);
            }
            float[][] out = new float[expected][];
            for (int i = 0; i < data.size(); i++) {
                JsonNode item = data.get(i);
                int index = item.has("index") ? item.get("index").asInt() : i;
                JsonNode vec = item.path("embedding");
                if (!vec.isArray() || vec.isEmpty() || index < 0 || index >= expected) {
                    throw new AiUnavailableException("Malformed embedding at position " + i);
                }
                float[] v = new float[vec.size()];
                for (int d = 0; d < v.length; d++) v[d] = (float) vec.get(d).asDouble();
                out[index] = v;
            }
            if (Arrays.stream(out).anyMatch(v -> v == null)) {
                throw new AiUnavailableException("Embeddings response is missing embeddings for some inputs");
            }
            return new ArrayList<>(Arrays.asList(out));
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AiUnavailableException("Could not parse embeddings response", e);
        }
    }

    /** The provider's error message (Gemini wraps it in an array), else the truncated body. */
    private String truncate(String s) {
        if (s == null) return "";
        try {
            JsonNode node = mapper.readTree(s);
            if (node.isArray() && !node.isEmpty()) node = node.get(0);
            String msg = node.path("error").path("message").asText(null);
            if (msg != null) return msg.length() > 600 ? msg.substring(0, 600) + "..." : msg;
        } catch (Exception ignored) {
            // not JSON
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
