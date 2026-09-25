package com.jobassistant.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobassistant.ai.AiMessage;
import com.jobassistant.ai.AiReply;
import com.jobassistant.ai.AiService;
import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.ai.ToolCall;
import com.jobassistant.ai.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conversational agent: the (fake) LLM decides whether to call backend tools; the tools run the
 * real hybrid RAG search against the real dataset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.chat.mode=agent")
class AgentChatTest {

    private static final Pattern JOB_ID = Pattern.compile("\"job_id\":(\\d+)");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockitoBean
    AiService aiService;

    /** Scripted model: given the conversation so far, return the next reply. */
    private Function<List<AiMessage>, AiReply> model;
    private final List<List<AiMessage>> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        requests.clear();
        when(aiService.isConfigured()).thenReturn(true);
        when(aiService.modelName()).thenReturn("fake-ling");
        when(aiService.chatWithTools(anyList(), anyList(), any())).thenAnswer(inv -> {
            List<AiMessage> msgs = new ArrayList<>(inv.getArgument(0));
            List<ToolDefinition> tools = inv.getArgument(1);
            assertThat(tools).extracting(ToolDefinition::name)
                    .containsExactlyInAnyOrder("search_jobs", "get_job_details", "compare_jobs");
            requests.add(msgs);
            return model.apply(msgs);
        });
    }

    private JsonNode chat(String conversationId, String message) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("message", message);
        body.put("debug", true);
        String json = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(json);
    }

    private static AiMessage last(List<AiMessage> msgs) {
        return msgs.get(msgs.size() - 1);
    }

    /** First call: request the tool. Second call (after the tool result): answer citing the returned ids. */
    private static Function<List<AiMessage>, AiReply> toolThenAnswer(String tool, String args) {
        return msgs -> {
            AiMessage lastMsg = last(msgs);
            if (!"tool".equals(lastMsg.role())) {
                return new AiReply(null, List.of(new ToolCall("call_1", tool, args)), "tool_calls");
            }
            List<String> ids = new ArrayList<>();
            Matcher m = JOB_ID.matcher(lastMsg.content());
            while (m.find() && ids.size() < 3) ids.add("Job #" + m.group(1));
            return new AiReply("Here are some good options: " + String.join(", ", ids) + ".", List.of(), "stop");
        };
    }

    @Test
    void smallTalkIsAnsweredByTheModelWithoutCallingToolsOrShowingJobs() throws Exception {
        model = msgs -> new AiReply("Hello! 👋 How can I help you with your job search today?", List.of(), "stop");

        JsonNode r = chat(null, "helo");

        assertThat(r.path("message").asText()).startsWith("Hello!");
        assertThat(r.path("intent").asText()).isEqualTo("GENERAL");
        assertThat(r.path("jobs")).isEmpty();
        assertThat(r.path("toolCalls")).isEmpty();
        assertThat(r.path("aiAvailable").asBoolean()).isTrue();
        // the model received the grounding rules and the user's message
        assertThat(requests.get(0).get(0).content()).contains("answer ONLY using the supplied retrieved context")
                .contains("WITHOUT calling any");
        assertThat(last(requests.get(0)).content()).isEqualTo("helo");
    }

    @Test
    void multiTurnChatKeepsHistoryAndOnlySearchesWhenTheUserAsksForJobs() throws Exception {
        // chats normally until the user asks for jobs, then calls search_jobs
        Function<List<AiMessage>, AiReply> search = toolThenAnswer("search_jobs",
                "{\"query\":\"React jobs in Bangalore\",\"skills\":[\"React\"],\"location\":\"Bangalore\"}");
        model = msgs -> {
            String user = msgs.stream().filter(m -> "user".equals(m.role())).reduce((a, b) -> b).orElseThrow().content();
            if (user.toLowerCase().contains("jobs")) return search.apply(msgs);
            if (user.toLowerCase().startsWith("hi")) return new AiReply("Hi! I'm your job search assistant.", List.of(), "stop");
            return new AiReply("Tailor your resume to each role and practise common interview questions.", List.of(), "stop");
        };

        JsonNode hi = chat(null, "hi");
        String conv = hi.path("conversationId").asText();
        assertThat(hi.path("message").asText()).startsWith("Hi!");
        assertThat(hi.path("jobs")).isEmpty();
        assertThat(hi.path("toolCalls")).isEmpty();

        JsonNode advice = chat(conv, "how do I prepare for an interview?");
        assertThat(advice.path("intent").asText()).isEqualTo("GENERAL");
        assertThat(advice.path("jobs")).isEmpty();
        assertThat(advice.path("toolCalls")).isEmpty();
        assertThat(advice.path("message").asText()).contains("interview");

        JsonNode jobs = chat(conv, "ok, show me React jobs in Bangalore");
        assertThat(jobs.path("intent").asText()).isEqualTo("NEW_SEARCH");
        assertThat(jobs.path("toolCalls")).hasSize(1);
        assertThat(jobs.path("jobs")).isNotEmpty();

        // the model saw the earlier turns of the conversation
        List<AiMessage> lastRequest = requests.get(requests.size() - 1);
        assertThat(lastRequest).extracting(AiMessage::content)
                .contains("hi", "Hi! I'm your job search assistant.", "how do I prepare for an interview?");
    }

    @Test
    void jobRequestMakesTheModelCallSearchJobsWhichRunsTheRealRagSearch() throws Exception {
        model = toolThenAnswer("search_jobs",
                "{\"query\":\"remote Java Spring Boot jobs\",\"skills\":[\"Java\",\"Spring Boot\"],\"remote\":true,\"min_salary_lpa\":12}");

        JsonNode r = chat(null, "I want remote Java Spring Boot jobs above 12 LPA");

        assertThat(r.path("intent").asText()).isEqualTo("NEW_SEARCH");
        assertThat(r.path("toolCalls")).hasSize(1);
        assertThat(r.path("toolCalls").get(0).path("name").asText()).isEqualTo("search_jobs");
        assertThat(r.path("toolCalls").get(0).path("ok").asBoolean()).isTrue();
        assertThat(r.path("jobs")).isNotEmpty();
        r.path("jobs").forEach(j -> {
            assertThat(j.path("remote").asBoolean()).isTrue();
            assertThat(j.path("salaryMax").asLong()).isGreaterThanOrEqualTo(1_200_000L);
            assertThat(j.path("skills").toString()).containsAnyOf("\"Java\"", "\"Spring Boot\"");
        });
        assertThat(r.path("message").asText()).startsWith("Here are some good options: Job #");
        assertThat(r.path("sources")).isNotEmpty();
        // the tool result the model saw came from vector retrieval + DB, not from the model
        String toolResult = last(requests.get(1)).content();
        assertThat(toolResult).contains("\"total_matches\"", "\"why_it_matches\"", "\"salary\":\"₹");
        assertThat(r.path("debug").path("retrievedChunks")).isNotEmpty();
        assertThat(r.path("debug").path("toolCalls")).hasSize(1);
    }

    @Test
    void followUpsUseConversationStateForRefineCompareAndDetails() throws Exception {
        model = toolThenAnswer("search_jobs", "{\"query\":\"remote AWS jobs\",\"skills\":[\"AWS\"],\"remote\":true}");
        JsonNode first = chat(null, "Show me remote jobs requiring AWS");
        String id = first.path("conversationId").asText();
        List<Long> shown = new ArrayList<>();
        first.path("jobs").forEach(j -> shown.add(j.path("id").asLong()));

        // refine: the model sees the numbered previous results in CONVERSATION STATE
        model = toolThenAnswer("search_jobs", "{\"query\":\"less experience\",\"max_required_experience\":4,\"refine_previous\":true}");
        JsonNode refined = chat(id, "Only show jobs with less than 5 years experience");
        assertThat(requests.get(requests.size() - 2).get(0).content()).contains("Jobs shown to the user", "Job #" + shown.get(0));
        assertThat(refined.path("intent").asText()).isEqualTo("REFINE");
        refined.path("jobs").forEach(j -> {
            assertThat(j.path("experienceMin").asInt()).isLessThanOrEqualTo(4);
            assertThat(j.path("remote").asBoolean()).isTrue();
            assertThat(j.path("skills").toString()).contains("\"AWS\"");
        });
        List<Long> refinedIds = new ArrayList<>();
        refined.path("jobs").forEach(j -> refinedIds.add(j.path("id").asLong()));

        model = toolThenAnswer("compare_jobs", "{\"job_ids\":[" + refinedIds.get(0) + "," + refinedIds.get(1) + "]}");
        JsonNode compared = chat(id, "Compare the first two");
        assertThat(compared.path("intent").asText()).isEqualTo("COMPARE");
        assertThat(compared.path("comparison").path("rows").get(0).path("values")).hasSize(2);

        model = toolThenAnswer("get_job_details", "{\"job_id\":" + refinedIds.get(0) + ",\"question\":\"benefits\"}");
        JsonNode details = chat(id, "What benefits does the first one offer?");
        assertThat(details.path("intent").asText()).isEqualTo("JOB_QUESTION");
        assertThat(details.path("jobs").get(0).path("id").asLong()).isEqualTo(refinedIds.get(0));
        assertThat(last(requests.get(requests.size() - 1)).content()).contains("\"benefits\"", "\"most_relevant_excerpts\"");
    }

    @Test
    void groundingGuardRejectsJobsTheToolsNeverReturned() throws Exception {
        model = msgs -> "tool".equals(last(msgs).role())
                ? new AiReply("Try Job #999 at Imaginary Corp!", List.of(), "stop")
                : new AiReply(null, List.of(new ToolCall("c1", "search_jobs", "{\"query\":\"python jobs\",\"skills\":[\"Python\"]}")), "tool_calls");

        JsonNode r = chat(null, "python jobs please");

        assertThat(r.path("message").asText()).doesNotContain("#999").doesNotContain("Imaginary");
        assertThat(r.path("jobs")).isNotEmpty();
        assertThat(r.path("debug").path("notes").toString()).contains("Grounding guard");
    }

    @Test
    void invalidToolArgumentsAreReportedBackToTheModel() throws Exception {
        model = msgs -> "tool".equals(last(msgs).role())
                ? new AiReply("Sorry, I couldn't find that job.", List.of(), "stop")
                : new AiReply(null, List.of(new ToolCall("c1", "get_job_details", "{\"job_id\":99999}")), "tool_calls");

        JsonNode r = chat(null, "tell me about job 99999");

        assertThat(r.path("toolCalls").get(0).path("ok").asBoolean()).isFalse();
        assertThat(last(requests.get(1)).content()).contains("does not exist");
        assertThat(r.path("message").asText()).isEqualTo("Sorry, I couldn't find that job.");
    }

    @Test
    void whenTheModelIsUnavailableTheDeterministicPipelineStillAnswers() throws Exception {
        doThrow(new AiUnavailableException("HTTP 429: Rate limit exceeded")).when(aiService).chatWithTools(anyList(), anyList(), any());
        doThrow(new AiUnavailableException("HTTP 429: Rate limit exceeded")).when(aiService).chat(anyList(), any());

        JsonNode r = chat(null, "Find React jobs in Bangalore");

        assertThat(r.path("aiAvailable").asBoolean()).isFalse();
        assertThat(r.path("notice").asText()).contains("temporarily unavailable");
        assertThat(r.path("jobs")).isNotEmpty();
        r.path("jobs").forEach(j -> assertThat(j.path("location").asText()).contains("Bangalore"));
        assertThat(r.path("debug").path("notes").toString()).contains("Falling back to the deterministic pipeline");
    }
}
