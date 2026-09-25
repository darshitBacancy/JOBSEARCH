package com.jobassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.config.HttpClientConfig;
import com.jobassistant.config.OpenRouterProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP clients against a local stub that speaks the (Gemini / OpenRouter)
 * OpenAI-compatible wire format.
 */
class OpenRouterClientsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String response = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenRouterProperties props(String key) {
        return new OpenRouterProperties(key, "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "inclusionai/ling-3.0-flash-fin:free", "test/embedding-model", 5, 0.2, 500, null, null);
    }

    private OpenRouterAiService chat(String key) {
        OpenRouterProperties p = props(key);
        return new OpenRouterAiService(new HttpClientConfig().openRouterRestClient(p), p, mapper);
    }

    private OpenRouterEmbeddingService embeddings(String key) {
        OpenRouterProperties p = props(key);
        return new OpenRouterEmbeddingService(new HttpClientConfig().openRouterRestClient(p), p, mapper);
    }

    @Test
    void chatSendsModelMessagesAndBearerKeyAndParsesTheReply() {
        response = """
                {"id":"x","choices":[{"message":{"role":"assistant","content":"  Hello from Ling  "}}]}""";

        String reply = chat("sk-test").chat(List.of(AiMessage.system("sys"), AiMessage.user("hi")));

        assertThat(reply).isEqualTo("Hello from Ling");
        assertThat(lastPath.get()).isEqualTo("/api/v1/chat/completions");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get().path("model").asText()).isEqualTo("inclusionai/ling-3.0-flash-fin:free");
        assertThat(lastBody.get().path("messages").get(1).path("content").asText()).isEqualTo("hi");
        // "reasoning" is OpenRouter-only; Gemini rejects unknown fields
        assertThat(lastBody.get().has("reasoning")).isFalse();
    }

    @Test
    void chatFailuresBecomeAiUnavailableException() {
        status = 429;
        response = "{\"error\":{\"message\":\"Rate limit exceeded\"}}";
        assertThatThrownBy(() -> chat("sk-test").chat(List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("429").hasMessageContaining("Rate limit");

        status = 200;
        response = "{\"choices\":[]}";
        assertThatThrownBy(() -> chat("sk-test").chat(List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnavailableException.class);

        response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null}}]}";
        assertThatThrownBy(() -> chat("sk-test").chat(List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("finish_reason=length");
    }

    @Test
    void missingApiKeyIsHandledWithoutCallingTheProvider() {
        OpenRouterAiService service = chat("");
        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.chat(List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("GEMINI_API_KEY");
        assertThatThrownBy(() -> embeddings(null).embed(List.of("x"))).isInstanceOf(AiUnavailableException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void embeddingsUseTheDedicatedEndpointAndConfiguredModelAndRespectIndexOrder() {
        response = """
                {"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}],"model":"test/embedding-model"}""";

        List<float[]> vectors = embeddings("sk-test").embed(List.of("first", "second"));

        assertThat(lastPath.get()).isEqualTo("/api/v1/embeddings");
        assertThat(lastBody.get().path("model").asText()).isEqualTo("test/embedding-model");
        assertThat(lastBody.get().path("input")).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f);
        assertThat(vectors.get(1)).containsExactly(0.0f, 1.0f);
    }

    @Test
    void embeddingCountMismatchIsRejected() {
        response = "{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}";
        assertThatThrownBy(() -> embeddings("sk-test").embed(List.of("a", "b")))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("expected 2");
    }

    @Test
    void toolCallingSendsToolDefinitionsAndParsesToolCalls() {
        response = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_7","type":"function","function":{"name":"search_jobs",
                  "arguments":"{\\"query\\":\\"remote java\\",\\"remote\\":true}"}}]}}]}""";
        ToolDefinition tool = new ToolDefinition("search_jobs", "Search jobs",
                java.util.Map.of("type", "object", "properties", java.util.Map.of()));

        AiReply reply = chat("sk-test").chatWithTools(List.of(
                AiMessage.user("find remote java jobs"),
                AiMessage.assistantToolCalls(null, List.of(new ToolCall("call_1", "get_job_details", "{\"job_id\":1}"))),
                AiMessage.toolResult("call_1", "{\"error\":\"x\"}")), List.of(tool), AiService.ChatOptions.defaults());

        assertThat(reply.hasToolCalls()).isTrue();
        assertThat(reply.toolCalls().get(0).id()).isEqualTo("call_7");
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("search_jobs");
        assertThat(reply.toolCalls().get(0).arguments()).contains("\"remote\":true");
        JsonNode body = lastBody.get();
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("search_jobs");
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(body.path("messages").get(1).path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("get_job_details");
        assertThat(body.path("messages").get(2).path("role").asText()).isEqualTo("tool");
        assertThat(body.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void geminiThoughtSignatureIsParsedAndEchoedBack() {
        response = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{
                  "extra_content":{"google":{"thought_signature":"SIG123"}},"id":"call_9","type":"function",
                  "function":{"name":"search_jobs","arguments":"{}"}}]}}]}""";
        ToolDefinition tool = new ToolDefinition("search_jobs", "Search jobs",
                java.util.Map.of("type", "object", "properties", java.util.Map.of()));
        OpenRouterAiService service = chat("key");

        AiReply first = service.chatWithTools(List.of(AiMessage.user("java jobs")), List.of(tool),
                AiService.ChatOptions.defaults());
        assertThat(first.toolCalls().get(0).extraContent()).contains("SIG123");

        response = "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}";
        service.chatWithTools(List.of(AiMessage.user("java jobs"), AiMessage.assistantToolCalls(null, first.toolCalls()),
                AiMessage.toolResult("call_9", "[]")), List.of(tool), AiService.ChatOptions.defaults());
        assertThat(lastBody.get().path("messages").get(1).path("tool_calls").get(0)
                .path("extra_content").path("google").path("thought_signature").asText()).isEqualTo("SIG123");
    }

    @Test
    void overloadedModelFallsBackToTheNextModel() {
        OpenRouterProperties p = new OpenRouterProperties("key", "http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1beta/openai", "primary", "emb", 5, 0.2, 500, null, null, List.of("backup"), null);
        OpenRouterAiService service = new OpenRouterAiService(new HttpClientConfig().openRouterRestClient(p), p, mapper);
        java.util.List<String> models = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            String model = mapper.readTree(exchange.getRequestBody()).path("model").asText();
            models.add(model);
            boolean ok = model.equals("backup");
            byte[] bytes = (ok ? "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"
                    : "[{\"error\":{\"code\":503,\"message\":\"high demand\"}}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 503, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        assertThat(service.chat(List.of(AiMessage.user("hi")))).isEqualTo("hi");
        assertThat(models).containsExactly("primary", "primary", "backup");
        assertThat(service.modelName()).isEqualTo("backup");
    }

    @Test
    void propertiesNeverPrintTheKey() {
        assertThat(props("sk-or-v1-supersecret").toString()).doesNotContain("supersecret").contains("***");
    }
}
