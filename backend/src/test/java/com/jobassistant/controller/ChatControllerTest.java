package com.jobassistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.AiMessage;
import com.jobassistant.ai.AiService;
import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.repository.JobRepository;
import com.jobassistant.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    private static final Pattern CONTEXT_JOB = Pattern.compile("\\[JOB #(\\d+)]");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JobRepository jobRepository;
    @MockitoBean
    AiService aiService;

    /** What the fake LLM returns for the extraction prompt, per user message. */
    private Function<String, String> extraction;
    /** Every context the fake LLM received for grounded generation. */
    private final List<String> generationContexts = new ArrayList<>();
    private final List<String> generationSystems = new ArrayList<>();
    private String answerOverride;

    @BeforeEach
    void fakeLlm() {
        extraction = msg -> "{\"intent\":\"NEW_SEARCH\"}";
        answerOverride = null;
        generationContexts.clear();
        generationSystems.clear();
        when(aiService.isConfigured()).thenReturn(true);
        when(aiService.modelName()).thenReturn("fake-ling");
        when(aiService.chat(anyList(), any())).thenAnswer(inv -> {
            List<AiMessage> messages = inv.getArgument(0);
            String system = messages.get(0).content();
            String last = messages.get(messages.size() - 1).content();
            if (system.startsWith("You convert")) {
                String current = last.substring(last.indexOf("CURRENT MESSAGE:") + "CURRENT MESSAGE:".length()).trim();
                return extraction.apply(current);
            }
            generationContexts.add(last);
            generationSystems.add(system);
            if (answerOverride != null) return answerOverride;
            List<String> refs = new ArrayList<>();
            Matcher m = CONTEXT_JOB.matcher(last);
            while (m.find()) refs.add("Job #" + m.group(1));
            return "Based on the available jobs, the best matches are " + String.join(", ", refs) + ".";
        });
    }

    private JsonNode chat(String conversationId, String message) throws Exception {
        return chat(conversationId, message, Map.of());
    }

    private JsonNode chat(String conversationId, String message, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("message", message);
        body.put("debug", true);
        body.putAll(extra);
        String json = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.readTree(json);
    }

    private static List<Long> ids(JsonNode jobs) {
        List<Long> out = new ArrayList<>();
        jobs.forEach(j -> out.add(j.path("id").asLong()));
        return out;
    }

    @Test
    void normalQuestionReturnsGroundedAnswerJobCardsAndSources() throws Exception {
        extraction = msg -> """
                {"intent":"NEW_SEARCH","standaloneQuery":"remote Java Spring Boot jobs","skills":["Java","Spring Boot"],
                 "keywords":[],"location":null,"remote":true,"experienceMin":null,"experienceMax":null,
                 "salaryMin":null,"salaryMax":null,"employmentType":null,"ordinals":[],"jobIds":[]}""";

        JsonNode r = chat(null, "Find remote Java Spring Boot jobs.");

        assertThat(r.path("intent").asText()).isEqualTo("NEW_SEARCH");
        assertThat(r.path("aiAvailable").asBoolean()).isTrue();
        assertThat(r.path("notice").isNull()).isTrue();
        assertThat(r.path("message").asText()).startsWith("Based on the available jobs");
        assertThat(r.path("jobs")).hasSizeBetween(1, 5);
        r.path("jobs").forEach(j -> {
            assertThat(j.path("remote").asBoolean()).isTrue();
            assertThat(j.path("skills").toString()).containsAnyOf("\"Java\"", "\"Spring Boot\"");
            assertThat(j.path("matchScore").asInt()).isBetween(0, 100);
            assertThat(j.path("matchReasons")).isNotEmpty();
            assertThat(j.path("salary").asText()).startsWith("₹").endsWith("LPA");
        });
        List<Long> sourceIds = new ArrayList<>();
        r.path("sources").forEach(s -> sourceIds.add(s.path("jobId").asLong()));
        assertThat(sourceIds).isEqualTo(ids(r.path("jobs")));
        assertThat(r.path("criteria").path("remote").asBoolean()).isTrue();

        // the LLM only saw the retrieved top jobs, never the whole database
        String context = generationContexts.get(0);
        assertThat(CONTEXT_JOB.matcher(context).results().count()).isEqualTo(r.path("jobs").size());
        assertThat(context).contains("USER QUESTION:", "criteria applied by the search engine: Java, Spring Boot · remote");
        assertThat(generationSystems.get(0)).contains("Answer ONLY using the supplied retrieved context",
                "Never create a job that does not exist in the retrieved context");

        JsonNode debug = r.path("debug");
        assertThat(debug.path("criteriaSource").asText()).isEqualTo("llm+rules");
        assertThat(debug.path("retrievedChunks")).isNotEmpty();
        assertThat(debug.path("contextSentToLlm").asText()).contains("[JOB #");
        assertThat(debug.path("answerSource").asText()).isEqualTo("llm");
    }

    @Test
    void followUpRefinesThePreviousResults() throws Exception {
        extraction = msg -> msg.startsWith("Only")
                ? "{\"intent\":\"REFINE\",\"standaloneQuery\":\"remote Java jobs above 15 LPA\",\"salaryMin\":1500000}"
                : "{\"intent\":\"NEW_SEARCH\",\"standaloneQuery\":\"remote Java jobs\",\"skills\":[\"Java\"],\"remote\":true}";

        JsonNode first = chat(null, "Find remote Java jobs.");
        String conversationId = first.path("conversationId").asText();
        JsonNode second = chat(conversationId, "Only those above ₹15 LPA.");

        assertThat(second.path("intent").asText()).isEqualTo("REFINE");
        assertThat(second.path("conversationId").asText()).isEqualTo(conversationId);
        assertThat(second.path("totalMatches").asInt()).isLessThanOrEqualTo(first.path("totalMatches").asInt());
        assertThat(second.path("jobs")).isNotEmpty();
        second.path("jobs").forEach(j -> {
            assertThat(j.path("remote").asBoolean()).isTrue();
            assertThat(j.path("skills").toString()).contains("\"Java\"");
            assertThat(j.path("salaryMax").asLong()).isGreaterThanOrEqualTo(1_500_000L);
        });
        // merged criteria keep the earlier constraints
        assertThat(second.path("criteria").path("skills").toString()).contains("Java");
        assertThat(second.path("criteria").path("salaryMin").asLong()).isEqualTo(1_500_000L);

        mvc.perform(get("/api/conversations/" + conversationId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"));
    }

    @Test
    void compareAndJobQuestionResolveReferencesToPreviousResults() throws Exception {
        extraction = msg -> {
            if (msg.startsWith("Compare")) return "{\"intent\":\"COMPARE\",\"ordinals\":[1,2,3]}";
            if (msg.startsWith("What skills")) return "{\"intent\":\"JOB_QUESTION\"}";
            return "{\"intent\":\"NEW_SEARCH\",\"standaloneQuery\":\"remote AWS jobs\",\"skills\":[\"AWS\"],\"remote\":true}";
        };
        JsonNode search = chat(null, "Show me remote jobs requiring AWS.");
        String id = search.path("conversationId").asText();
        List<Long> shown = ids(search.path("jobs"));

        JsonNode compare = chat(id, "Compare the first three jobs.");
        assertThat(compare.path("intent").asText()).isEqualTo("COMPARE");
        assertThat(ids(compare.path("jobs"))).isEqualTo(shown.subList(0, 3));
        JsonNode rows = compare.path("comparison").path("rows");
        List<String> labels = new ArrayList<>();
        rows.forEach(row -> {
            labels.add(row.path("label").asText());
            assertThat(row.path("values")).hasSize(3);
        });
        assertThat(labels).contains("Title", "Company", "Location", "Remote", "Experience", "Salary", "Skills", "Employment");

        JsonNode question = chat(id, "What skills does this job require?");
        assertThat(question.path("intent").asText()).isEqualTo("JOB_QUESTION");
        assertThat(ids(question.path("jobs"))).containsExactly(shown.get(0));
        String context = generationContexts.get(generationContexts.size() - 1);
        String skills = String.join(", ", jobRepository.findById(shown.get(0)).orElseThrow().getSkills());
        assertThat(context).contains("[JOB #" + shown.get(0) + "]", "Skills: " + skills);
    }

    @Test
    void emptyResultsAreReportedHonestlyWithoutCallingTheLlmForAnAnswer() throws Exception {
        extraction = msg -> "{\"intent\":\"NEW_SEARCH\",\"keywords\":[\"COBOL\",\"mainframe\"],\"standaloneQuery\":\"COBOL mainframe jobs\"}";

        JsonNode r = chat(null, "Find COBOL mainframe jobs");

        assertThat(r.path("jobs")).isEmpty();
        assertThat(r.path("totalMatches").asInt()).isZero();
        assertThat(r.path("message").asText()).contains("couldn't find any jobs");
        assertThat(generationContexts).isEmpty();
    }

    @Test
    void aiFailureFallsBackToDeterministicSearch() throws Exception {
        doThrow(new AiUnavailableException("HTTP 503")).when(aiService).chat(anyList(), any());

        JsonNode r = chat(null, "Find React jobs in Bangalore.");

        assertThat(r.path("aiAvailable").asBoolean()).isFalse();
        assertThat(r.path("notice").asText()).isEqualTo(ChatService.AI_UNAVAILABLE_NOTICE);
        assertThat(r.path("jobs")).isNotEmpty();
        r.path("jobs").forEach(j -> assertThat(j.path("location").asText()).contains("Bangalore"));
        assertThat(r.path("message").asText()).startsWith("I found");
        assertThat(r.path("debug").path("criteriaSource").asText()).isEqualTo("rules");
        assertThat(r.path("debug").path("answerSource").asText()).isEqualTo("fallback");
    }

    @Test
    void missingApiKeyStillServesSearchResults() throws Exception {
        when(aiService.isConfigured()).thenReturn(false);

        JsonNode r = chat(null, "Show jobs above ₹12 LPA");

        assertThat(r.path("aiAvailable").asBoolean()).isFalse();
        assertThat(r.path("notice").asText()).contains("temporarily unavailable");
        assertThat(r.path("jobs")).isNotEmpty();
        r.path("jobs").forEach(j -> assertThat(j.path("salaryMax").asLong()).isGreaterThanOrEqualTo(1_200_000L));
    }

    @Test
    void groundingGuardDiscardsAnswersThatInventJobs() throws Exception {
        extraction = msg -> "{\"intent\":\"NEW_SEARCH\",\"skills\":[\"Python\"]}";
        answerOverride = "I recommend Job #999 at Imaginary Corp, paying ₹99 LPA.";

        JsonNode r = chat(null, "Find Python jobs");

        assertThat(r.path("message").asText()).doesNotContain("#999").doesNotContain("Imaginary");
        assertThat(r.path("message").asText()).startsWith("I found");
        assertThat(r.path("debug").path("notes").toString()).contains("Grounding guard");
    }

    @Test
    void smallTalkIsAnsweredByTheLlmWithoutShowingJobs() throws Exception {
        extraction = msg -> "{\"intent\":\"GENERAL\"}";
        answerOverride = "Hello there! How can I help with your job search today?";
        JsonNode r = chat(null, "helo");
        assertThat(r.path("intent").asText()).isEqualTo("GENERAL");
        assertThat(r.path("jobs")).isEmpty();
        assertThat(r.path("sources")).isEmpty();
        assertThat(r.path("message").asText()).isEqualTo(answerOverride); // generated, not canned
        assertThat(generationSystems.get(0)).contains("small talk");
    }

    @Test
    void smallTalkWhenAiIsDownGivesAFriendlyOfflineReplyInsteadOfASearch() throws Exception {
        doThrow(new AiUnavailableException("HTTP 429: Rate limit exceeded")).when(aiService).chat(anyList(), any());
        JsonNode r = chat(null, "hi");
        assertThat(r.path("intent").asText()).isEqualTo("GENERAL");
        assertThat(r.path("jobs")).isEmpty();
        assertThat(r.path("message").asText()).startsWith("Hi there!").contains("search the job listings");
        assertThat(r.path("aiAvailable").asBoolean()).isFalse();
        assertThat(r.path("notice").asText()).contains("temporarily unavailable");
        assertThat(r.path("debug").path("notes").toString()).contains("HTTP 429");

        JsonNode thanks = chat(r.path("conversationId").asText(), "thanks");
        assertThat(thanks.path("jobs")).isEmpty();
        assertThat(thanks.path("message").asText()).startsWith("You're welcome!");
    }

    @Test
    void invalidRequestsAreRejectedWithFieldErrors() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.message").exists());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("message", "x".repeat(2001)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
        mvc.perform(post("/api/jobs/compare").contentType(MediaType.APPLICATION_JSON).content("{\"jobIds\":[101]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/chat").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/rag/retrieve")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/jobs/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Job 99999 was not found"));
    }

    @Test
    void jobEndpointsReturnDetailsListAndComparison() throws Exception {
        mvc.perform(get("/api/jobs/109"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.requirements").isArray())
                .andExpect(jsonPath("$.benefits").isArray())
                .andExpect(jsonPath("$.applicationUrl").value("https://example.com/demo-jobs/109"))
                .andExpect(jsonPath("$.dataNotice").value(org.hamcrest.Matchers.containsString("fictional")));
        mvc.perform(get("/api/jobs?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.totalElements").value(120));
        mvc.perform(post("/api/jobs/compare").contentType(MediaType.APPLICATION_JSON).content("{\"jobIds\":[101,102,103]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(3))
                .andExpect(jsonPath("$.rows[0].values.length()").value(3))
                .andExpect(jsonPath("$.summary").isNotEmpty());
        mvc.perform(post("/api/jobs/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"remote Java jobs\",\"salaryMin\":1500000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].remote").value(true))
                .andExpect(jsonPath("$.criteria.salaryMin").value(1500000));
    }

    @Test
    void conversationsCanBeListedRenamedAndDeleted() throws Exception {
        String id = chat(null, "Find   remote Java jobs in Pune").path("conversationId").asText();

        JsonNode list = mapper.readTree(mvc.perform(get("/api/conversations")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode n : list) if (n.path("id").asText().equals(id)) row = n;
        assertThat(row).isNotNull();
        assertThat(row.path("title").asText()).isEqualTo("Find remote Java jobs in Pune");
        assertThat(row.path("messageCount").asLong()).isEqualTo(2);

        mvc.perform(put("/api/conversations/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Pune Java\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/conversations")).andExpect(jsonPath("$[?(@.id == '" + id + "')].title").value("Pune Java"));

        mvc.perform(delete("/api/conversations/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/conversations/" + id + "/messages")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/conversations/" + id)).andExpect(status().isNotFound());
    }
}
