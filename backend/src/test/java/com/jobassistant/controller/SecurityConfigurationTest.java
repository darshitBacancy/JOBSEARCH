package com.jobassistant.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A key is configured but the provider is unreachable: the key must never appear in any
 * response (including debug traces), and the assistant must degrade gracefully.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "openrouter.api-key=sk-or-v1-TOP-SECRET-TEST-KEY",
        "openrouter.base-url=http://127.0.0.1:9/api/v1",
        "openrouter.timeout-seconds=2"
})
class SecurityConfigurationTest {

    private static final String SECRET = "TOP-SECRET-TEST-KEY";

    @Autowired
    MockMvc mvc;

    private String body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void apiKeyIsNeverExposed() throws Exception {
        String systemStatus = body(get("/api/system/status"));
        assertThat(systemStatus).contains("\"aiConfigured\":true").doesNotContain(SECRET);

        String chat = body(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Find remote Java jobs\",\"debug\":true}"));
        assertThat(chat).doesNotContain(SECRET);

        assertThat(body(get("/api/rag/traces"))).doesNotContain(SECRET);
        assertThat(body(get("/api/rag/status"))).doesNotContain(SECRET);
    }

    @Test
    void unreachableProviderIsHandledGracefully() throws Exception {
        String chat = body(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Find React jobs in Bangalore\"}"));
        assertThat(chat).contains("\"aiAvailable\":false").contains("temporarily unavailable").contains("\"jobs\":[{");
    }
}
