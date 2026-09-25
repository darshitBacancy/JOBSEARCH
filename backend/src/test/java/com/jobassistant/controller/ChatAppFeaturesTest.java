package com.jobassistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.AiService;
import com.jobassistant.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chat-app features around the conversation: message ids, feedback, regenerate, edit-and-resend,
 * clearing history, saved jobs and card lookup. The LLM is not configured, so the deterministic
 * pipeline answers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatAppFeaturesTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JobRepository jobRepository;
    @MockitoBean
    AiService aiService;

    @BeforeEach
    void noLlm() {
        when(aiService.isConfigured()).thenReturn(false);
        when(aiService.modelName()).thenReturn("none");
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    private JsonNode chat(String conversationId, String message) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("message", message,
                "conversationId", conversationId == null ? "" : conversationId));
        if (conversationId == null) body = mapper.writeValueAsString(java.util.Map.of("message", message));
        return json(mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode history(String id) throws Exception {
        return json(mvc.perform(get("/api/conversations/" + id + "/messages")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void chatResponseCarriesStoredMessageIds() throws Exception {
        JsonNode res = chat(null, "remote java jobs");
        String id = res.path("conversationId").asText();
        JsonNode stored = history(id);

        assertThat(res.path("userMessageId").asLong()).isEqualTo(stored.get(0).path("id").asLong());
        assertThat(res.path("assistantMessageId").asLong()).isEqualTo(stored.get(1).path("id").asLong());
        assertThat(res.path("messageId").asLong()).isEqualTo(res.path("assistantMessageId").asLong());
        assertThat(stored.get(1).has("feedback")).isTrue();
        assertThat(stored.get(1).path("feedback").isNull()).isTrue();
    }

    @Test
    void feedbackIsStoredClearedAndValidated() throws Exception {
        JsonNode res = chat(null, "python jobs");
        long reply = res.path("assistantMessageId").asLong();
        String id = res.path("conversationId").asText();

        mvc.perform(put("/api/messages/" + reply + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":\"up\"}")).andExpect(status().isNoContent());
        assertThat(history(id).get(1).path("feedback").asText()).isEqualTo("up");

        mvc.perform(put("/api/messages/" + reply + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":null}")).andExpect(status().isNoContent());
        assertThat(history(id).get(1).path("feedback").isNull()).isTrue();

        mvc.perform(put("/api/messages/" + reply + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":\"meh\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/messages/99999999/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":\"down\"}")).andExpect(status().isNotFound());
    }

    @Test
    void regenerateReplacesTheLastExchange() throws Exception {
        String id = chat(null, "react jobs").path("conversationId").asText();
        chat(id, "devops jobs in Pune");
        assertThat(history(id)).hasSize(4);

        JsonNode res = json(mvc.perform(post("/api/chat/regenerate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + id + "\",\"debug\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode stored = history(id);
        assertThat(stored).hasSize(4);
        assertThat(stored.get(2).path("message").asText()).isEqualTo("devops jobs in Pune");
        assertThat(res.path("conversationId").asText()).isEqualTo(id);
        assertThat(res.path("assistantMessageId").asLong()).isEqualTo(stored.get(3).path("id").asLong());

        mvc.perform(post("/api/chat/regenerate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"no-such-conversation\"}")).andExpect(status().isNotFound());
    }

    @Test
    void regenerateWithoutAUserMessageIsABadRequest() throws Exception {
        String id = chat(null, "java jobs").path("conversationId").asText();
        long first = history(id).get(0).path("id").asLong();
        mvc.perform(post("/api/conversations/" + id + "/truncate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMessageId\":" + first + "}")).andExpect(status().isNoContent());

        mvc.perform(post("/api/chat/regenerate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + id + "\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void truncateDeletesTheMessageAndEverythingAfterIt() throws Exception {
        String id = chat(null, "java jobs").path("conversationId").asText();
        JsonNode second = chat(id, "only remote ones");
        long from = second.path("userMessageId").asLong();

        mvc.perform(post("/api/conversations/" + id + "/truncate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMessageId\":" + from + "}")).andExpect(status().isNoContent());
        JsonNode stored = history(id);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).path("message").asText()).isEqualTo("java jobs");

        mvc.perform(post("/api/conversations/" + id + "/truncate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMessageId\":99999999}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/nope/truncate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMessageId\":1}")).andExpect(status().isNotFound());

        // a message of another conversation can't be used to truncate this one
        String other = chat(null, "python jobs").path("conversationId").asText();
        long otherMsg = history(other).get(0).path("id").asLong();
        mvc.perform(post("/api/conversations/" + id + "/truncate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMessageId\":" + otherMsg + "}")).andExpect(status().isNotFound());
        assertThat(history(other)).hasSize(2);
    }

    @Test
    void deleteAllConversationsClearsHistory() throws Exception {
        String id = chat(null, "go jobs").path("conversationId").asText();
        mvc.perform(delete("/api/conversations")).andExpect(status().isNoContent());
        mvc.perform(get("/api/conversations")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/conversations/" + id + "/messages")).andExpect(status().isNotFound());
    }

    @Test
    void jobCardsAreReturnedInRequestOrderSkippingUnknownIds() throws Exception {
        List<Long> ids = jobRepository.findAll().stream().map(j -> j.getId()).sorted().limit(3).toList();
        String body = "{\"jobIds\":[" + ids.get(2) + ",999999," + ids.get(0) + "]}";

        JsonNode cards = json(mvc.perform(post("/api/jobs/cards").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).path("id").asLong()).isEqualTo(ids.get(2));
        assertThat(cards.get(1).path("id").asLong()).isEqualTo(ids.get(0));
        assertThat(cards.get(0).path("title").asText()).isNotBlank();

        String tooMany = "{\"jobIds\":[" + String.join(",", java.util.Collections.nCopies(51, "1")) + "]}";
        mvc.perform(post("/api/jobs/cards").contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savedJobsCanBeAddedListedAndRemovedIdempotently() throws Exception {
        List<Long> ids = jobRepository.findAll().stream().map(j -> j.getId()).sorted().limit(2).toList();
        for (Long id : ids) mvc.perform(delete("/api/saved-jobs/" + id)).andExpect(status().isNoContent());

        mvc.perform(put("/api/saved-jobs/" + ids.get(0))).andExpect(status().isNoContent());
        Thread.sleep(5);
        mvc.perform(put("/api/saved-jobs/" + ids.get(1))).andExpect(status().isNoContent());
        mvc.perform(put("/api/saved-jobs/" + ids.get(1))).andExpect(status().isNoContent());

        JsonNode saved = json(mvc.perform(get("/api/saved-jobs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<Long> savedIds = new java.util.ArrayList<>();
        saved.forEach(n -> savedIds.add(n.path("id").asLong()));
        assertThat(savedIds).containsSubsequence(ids.get(1), ids.get(0));
        assertThat(savedIds.stream().filter(ids.get(1)::equals).count()).isEqualTo(1);

        mvc.perform(delete("/api/saved-jobs/" + ids.get(0))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/saved-jobs/" + ids.get(0))).andExpect(status().isNoContent());
        mvc.perform(get("/api/saved-jobs")).andExpect(jsonPath("$[?(@.id == " + ids.get(0) + ")]").isEmpty());

        mvc.perform(put("/api/saved-jobs/999999")).andExpect(status().isNotFound());
    }
}
