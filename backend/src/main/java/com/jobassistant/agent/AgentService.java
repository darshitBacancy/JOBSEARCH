package com.jobassistant.agent;

import com.jobassistant.ai.AiMessage;
import com.jobassistant.ai.AiReply;
import com.jobassistant.ai.AiService;
import com.jobassistant.ai.AiUnavailableException;
import com.jobassistant.ai.ToolCall;
import com.jobassistant.dto.ToolCallRecord;
import com.jobassistant.entity.Job;
import com.jobassistant.mapper.JobMapper;
import com.jobassistant.repository.JobRepository;
import com.jobassistant.service.FallbackAnswerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Conversational RAG agent. The LLM talks to the user and decides when to call the backend
 * tools ({@link JobTools}): search_jobs (hybrid vector + SQL retrieval), get_job_details
 * (listing + vector-retrieved excerpts) and compare_jobs. Tool results are fed back to the model,
 * which answers from them only. Small talk needs no tools, so no job cards are shown.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_ROUNDS = 4;
    private static final Pattern JOB_REF = Pattern.compile("(?:#|\\bjob\\s+(?:id\\s*)?#?)\\s*(\\d{3,})", Pattern.CASE_INSENSITIVE);

    static final String SYSTEM_PROMPT = """
            You are a friendly, conversational Job Search Assistant chatbot. You help people explore a knowledge base
            of demo job listings (all fictional sample data, in India, salaries in LPA = lakhs per annum).

            How to behave:
            - You are a real chatbot first. Greetings ("hi", "hello"), thanks, "how are you", "who are you",
              "what can you do" and other small talk: reply naturally and warmly in 1-3 sentences WITHOUT calling any
              tool and without listing jobs. You can briefly mention what you can help with.
            - General career questions (interview tips, resume advice, which skills to learn, what a DevOps engineer
              does, ...) can be answered directly from your general knowledge WITHOUT calling tools. Keep it short and
              practical, and offer to look for matching jobs if relevant.
            - Only call tools when the user wants job listings or information about the listings:
              * find, filter or narrow down jobs -> search_jobs. Put their requirements into the arguments (skills,
                location, remote, experience_years, min_salary_lpa, ...). Use refine_previous=true when they narrow
                the previous results ("only those above 15 LPA", "which of them are remote").
              * a question about one specific job -> get_job_details (pass the user's question).
              * compare jobs -> compare_jobs with their ids. Resolve references like "the first three" or
                "the second one" using CONVERSATION STATE below.
            - Use the earlier messages of the conversation: follow-ups like "and in Pune?" or "what about remote
              ones?" refer to the previous request.
            - If a job request is ambiguous, ask a short clarifying question instead of guessing.

            Grounding rules for job listings (very important):
            - For anything about specific jobs, answer ONLY using the supplied retrieved context: the tool results of
              this conversation and the CONVERSATION STATE. If information is not present, say that the information
              is not available.
            - Never invent missing job information - companies, salaries, locations, skills, experience, benefits,
              application links or availability. Never create a job that does not exist in the tool results.
            - Refer to jobs as "Job #<id>". Use numbers exactly as the tools returned them.

            Style: concise (usually under 150 words), friendly, plain text; you may use **bold** and short lists.
            The app already shows job cards for search results, so summarise the best matches and why they fit
            instead of repeating every field. Match percentages are relative indicators, not objective ratings.
            """;

    public record Result(boolean handled, String message, boolean llmFailed, String note) {
    }

    private final AiService aiService;
    private final JobTools tools;
    private final FallbackAnswerBuilder fallback;
    private final JobRepository jobRepository;

    public AgentService(AiService aiService, JobTools tools, FallbackAnswerBuilder fallback, JobRepository jobRepository) {
        this.aiService = aiService;
        this.tools = tools;
        this.fallback = fallback;
        this.jobRepository = jobRepository;
    }

    /**
     * Runs the tool-calling loop. {@code handled=false} means the LLM could not be used at all and
     * the caller should fall back to the deterministic pipeline.
     */
    public Result run(String userMessage, List<AiMessage> history, AgentTurn turn) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(AiMessage.system(SYSTEM_PROMPT + "\n" + conversationState(turn)));
        messages.addAll(history);
        messages.add(AiMessage.user(userMessage));

        for (int round = 0; round < MAX_ROUNDS; round++) {
            AiReply reply;
            long t = System.currentTimeMillis();
            try {
                reply = aiService.chatWithTools(messages, tools.definitions(), AiService.ChatOptions.defaults());
            } catch (AiUnavailableException e) {
                log.warn("Agent LLM call failed (round {}): {}", round, e.getMessage());
                if (turn.toolCalls().isEmpty()) return new Result(false, null, true, "Agent unavailable: " + e.getMessage());
                return new Result(true, deterministicAnswer(turn, userMessage), true,
                        "LLM failed after tools ran (" + e.getMessage() + "): answered from tool results");
            }
            turn.trace().timing("llm_round_" + (round + 1), t);

            if (!reply.hasToolCalls()) {
                String text = reply.content().trim();
                Set<Long> unknown = referencedJobIds(text);
                unknown.removeAll(turn.knownJobIds());
                if (!unknown.isEmpty()) {
                    log.warn("Grounding guard rejected agent answer referencing unknown jobs {}", unknown);
                    String safe = deterministicAnswer(turn, userMessage);
                    return new Result(true, safe != null ? safe : "Sorry, I couldn't verify that answer against the job "
                            + "listings. Could you rephrase your question?", false,
                            "Grounding guard: answer referenced job(s) " + unknown + " not returned by any tool; discarded");
                }
                return new Result(true, text, false, null);
            }

            messages.add(AiMessage.assistantToolCalls(reply.content(), reply.toolCalls()));
            for (ToolCall call : reply.toolCalls()) {
                long start = System.currentTimeMillis();
                String result;
                boolean ok = true;
                try {
                    result = tools.execute(call, turn);
                    ok = !result.startsWith("{\"error\"");
                } catch (RuntimeException e) {
                    log.warn("Tool {} failed", call.name(), e);
                    result = "{\"error\":\"tool failed: " + e.getClass().getSimpleName() + "\"}";
                    ok = false;
                }
                long took = System.currentTimeMillis() - start;
                turn.addToolCall(new ToolCallRecord(call.name(), call.arguments(), summarise(call.name(), turn, ok, result),
                        took, ok));
                turn.trace().note("Tool " + call.name() + " " + call.arguments() + " -> " + (ok ? "ok" : result));
                messages.add(AiMessage.toolResult(call.id(), result));
            }
        }
        return new Result(true, deterministicAnswer(turn, userMessage), false,
                "Agent reached the maximum number of tool rounds; answered from tool results");
    }

    /** Numbered view of what the conversation points at, so the model can resolve "the first three". */
    private String conversationState(AgentTurn turn) {
        StringBuilder sb = new StringBuilder("CONVERSATION STATE:\n");
        if (turn.shownJobIds().isEmpty()) {
            sb.append("- No jobs have been shown yet.\n");
        } else {
            Map<Long, Job> byId = jobRepository.findAllById(turn.shownJobIds()).stream()
                    .collect(Collectors.toMap(Job::getId, Function.identity()));
            sb.append("- Last search: ").append(fallback.describe(turn.lastCriteria())).append('\n');
            sb.append("- Jobs shown to the user (in order):\n");
            int i = 1;
            for (Long id : turn.shownJobIds()) {
                Job j = byId.get(id);
                if (j == null) continue;
                sb.append("  ").append(i++).append(". Job #").append(j.getId()).append(' ').append(j.getTitle())
                        .append(" - ").append(j.getCompany()).append(" (").append(j.getLocation())
                        .append(j.isRemote() ? ", remote" : "").append(", ")
                        .append(JobMapper.formatSalary(j.getSalaryMin(), j.getSalaryMax(), j.getCurrency())).append(")\n");
            }
        }
        if (turn.focusJobId() != null) {
            sb.append("- The user is currently asking about Job #").append(turn.focusJobId())
                    .append(" (\"this job\" refers to it).\n");
        }
        String filters = turn.filters() == null ? "" : fallback.describe(turn.filters().toCriteria());
        if (!filters.isBlank()) sb.append("- Filters selected in the UI (applied automatically to searches): ").append(filters).append('\n');
        return sb.toString();
    }

    /** Fact-based answer built from what the tools returned (used when the LLM can't finish the turn). */
    private String deterministicAnswer(AgentTurn turn, String userMessage) {
        return switch (turn.lastAction()) {
            case SEARCH, REFINE -> turn.searchTop().isEmpty()
                    ? fallback.noResults(turn.searchCriteria())
                    : fallback.searchAnswer(turn.searchTop(), turn.totalMatches(), turn.searchCriteria(), turn.relaxedFilters());
            case COMPARE -> "Here is a side-by-side comparison of " + turn.cards().stream()
                    .map(c -> "Job #" + c.id()).collect(Collectors.joining(", ")) + ".\n\n" + turn.comparison().summary();
            case DETAILS -> fallback.jobAnswer(turn.detailJob(), userMessage);
            case NONE -> null;
        };
    }

    private static String summarise(String tool, AgentTurn turn, boolean ok, String result) {
        if (!ok) return "error: " + (result.length() > 120 ? result.substring(0, 120) + "..." : result);
        return switch (tool) {
            case JobTools.SEARCH_JOBS -> turn.totalMatches() + " match(es), showing " + turn.cards().size();
            case JobTools.GET_JOB_DETAILS -> "loaded Job #" + turn.focusJobId();
            case JobTools.COMPARE_JOBS -> "compared " + turn.cards().size() + " jobs";
            default -> "done";
        };
    }

    static Set<Long> referencedJobIds(String text) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher m = JOB_REF.matcher(text);
        while (m.find()) ids.add(Long.parseLong(m.group(1)));
        return ids;
    }
}
