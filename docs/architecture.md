# Architecture notes

This document complements the [README](../README.md) with the internal design: data model, the life of a chat
turn, indexing, ranking and failure handling.

## 1. Components

```
                        ┌──────────────────────────── Spring Boot (single app) ────────────────────────────┐
React (Vite)            │                                                                                  │
 ChatWindow ─┐          │  ChatController ──► ChatService ──► AgentService ──► AiService.chatWithTools ──┼──► Gemini
             │          │                          │           └─► JobTools (search_jobs / get_job_details /  │     /chat/completions
             │          │                          │               compare_jobs) ──► RagService, ComparisonService
             │          │                          │  fallback: CriteriaExtractionService ──► AiService     │
 JobCard     │  /api    │        │                 │              (LLM JSON + RuleBasedCriteriaParser)      │     /chat/completions
 JobDetails  ├─────────►│  JobController           │                                                       │
 JobComparison          │  RagController           ├──► RagService ──► HybridSearchService                 │
 RagInspector│          │  SystemController        │        │              ├─ JobSpecifications (SQL, H2)   │
 Sidebar     │          │                          │        │              ├─ Retriever ─► EmbeddingService ┼──► Gemini
             │          │                          │        │              │      └──────► InMemoryVectorStore   /embeddings
             │          │                          │        │              └─ RelevanceScorer               │
             │          │                          │        └─ buildContext / generateGroundedAnswer ──► AiService
             │          │                          ├──► ComparisonService                                    │
             │          │                          ├──► ConversationService (conversation, chat_message)     │
             │          │                          └──► RagTraceStore (observability)                        │
             │          │  IndexingService ◄── IndexingStartupRunner / POST /api/rag/reindex                │
             │          │     JobDataLoader (data/jobs.json → job) → JobDocumentBuilder → EmbeddingService  │
             │          │     → InMemoryVectorStore (+ snapshot file)                                       │
             │          └──────────────────────────────────────────────────────────────────────────────────┘
```

**LLM provider.** `OpenRouterAiService` and `OpenRouterEmbeddingService` speak the OpenAI wire format to whatever
`openrouter.base-url` points at. The default is Google Gemini's OpenAI-compatible endpoint,
`https://generativelanguage.googleapis.com/v1beta/openai`. The class names and the `openrouter.` property prefix are
historical; OpenRouter still works by setting `LLM_BASE_URL=https://openrouter.ai/api/v1` and `OPENROUTER_API_KEY`.
Gemini-specific handling:

| Concern | Handling |
|---|---|
| Models | chat `gemini-flash-latest`; on a retryable failure (429/5xx/network) it is retried once, then `openrouter.fallback-chat-models` (`gemini-3.5-flash`, `gemini-flash-lite-latest`) are tried in order. `modelName()` reports the model that last answered. |
| Thought signatures | Gemini 3 returns `extra_content.google.thought_signature` on every tool call and rejects the next turn (HTTP 400) if it is missing. `ToolCall.extraContent` keeps the raw JSON and it is echoed back on the assistant message. |
| Request fields | OpenRouter-only `reasoning` is sent only when the base URL is OpenRouter (Gemini rejects unknown fields). For Gemini, `reasoning_effort=low` limits thinking so answers fit in `max-tokens`. |
| Errors | Gemini wraps errors in a one-element JSON array; both clients unwrap it to get `error.message`. |
| Embeddings | `gemini-embedding-001`, 3072-d. The free tier allows ~100 texts/min. |
| Key | `GEMINI_API_KEY` from a git-ignored `.env` (root or `backend/`, loaded via `spring.config.import`) or the environment. |

## 2. Data model

**H2 (relational)**

| Table | Key columns | Notes |
|---|---|---|
| `job` | `id`, `title`, `company`, `location`, `remote`, `employment_type`, `experience_min/max`, `salary_min/max` (annual INR), `currency`, `skills` (JSON), `description`, `requirements`/`responsibilities`/`benefits` (JSON), `posted_date`, `application_url` | Source of truth. Indexed on remote, salary, experience. |
| `conversation` | `id` (UUID), `title`, `created_at`, `updated_at`, `last_criteria_json`, `last_semantic_query`, `last_candidate_job_ids`, `last_shown_job_ids`, `focus_job_id` | Follow-up state: what "those", "the first three" and "this job" refer to. `title` is set from the first user message (max 60 chars) and can be renamed; `updated_at` is bumped on every message, which orders the sidebar. |
| `chat_message` | `id`, `conversation_id`, `role` (USER/ASSISTANT), `message`, `job_ids`, `feedback` (`up`/`down`/null), `timestamp` | Full history; only the last `rag.history-window` messages go to the LLM. `job_ids` lets the UI restore job cards (`POST /api/jobs/cards`). |
| `saved_job` | `job_id` (PK), `saved_at` | Bookmarked jobs (global; there is no authentication). |

Schema is created by Hibernate (`ddl-auto=update`). Browse it at `http://localhost:9091/h2-console`
(JDBC URL `jdbc:h2:file:./db/jobsearch`, user `sa`, empty password) while the backend runs.

**In-memory vector store**

| Field | Example |
|---|---|
| `documentId` | `job-109-requirements-0` |
| `jobId` | `109` |
| `section` | `OVERVIEW` · `SKILLS` · `REQUIREMENTS` · `RESPONSIBILITIES` · `BENEFITS` |
| `chunkText` | `Job: Senior Backend Engineer (Java) at Kartwheel Commerce Labs (Bangalore, India, remote)\nRequirements:\n- 3+ years ...` |
| `embedding` | float[] (L2-normalised) |
| `metadata` | `jobId, title, company, location, skills, remote, section` |

Index-level info (`IndexInfo`): embedding provider, model, dimensions, dataset hash (SHA-256 of `jobs.json` + chunking
version), counts, timestamp. It is stored in the snapshot header and decides whether a restart can reuse the snapshot.

## 3. Life of a chat turn

### 3a. Agent mode (default, `app.chat.mode=agent`)

1. `ChatController` validates the request; `ConversationService` stores the user message and loads the bounded
   history window and follow-up state.
2. `AgentService` sends Gemini:
   - the system prompt: behaviour, when to use tools, and grounding rules for job facts
   - a **CONVERSATION STATE** block: last search criteria, a numbered list of the jobs shown last, the focus job
   - the history and the user message
   - three tool definitions (`JobTools`)

   Greetings, small talk and general career questions (interviews, resumes, which skills to learn) are answered
   directly without tools. Tools are called only when the user wants job listings, details or a comparison.

   | Tool | Arguments | Backend work | Result sent back to the model |
   |---|---|---|---|
   | `search_jobs` | `query` (required), `skills`, `location`, `remote`, `experience_years`, `max_required_experience`, `min_salary_lpa`, `employment_type`, `refine_previous` | query also parsed by `RuleBasedCriteriaParser`; merged with previous criteria + candidate restriction when `refine_previous`; `HybridSearchService` (SQL + vector); conversation state saved | total matches, criteria applied, relaxed filters, top 5 jobs with facts, match score, reasons, gaps, best excerpt |
   | `get_job_details` | `job_id`, `question` | DB listing + vector retrieval restricted to that job (top 4 chunks); focus saved | full listing + most relevant excerpts with similarity |
   | `compare_jobs` | `job_ids` (2-4) | comparison table from DB (`ComparisonService.table`) | facts per job + computed comparison facts |
3. If the model returns tool calls, they are executed and their JSON results are appended as `tool` messages; the loop
   runs at most 4 rounds. Small talk ends immediately with a text reply (no tools, no job cards).
4. The final text passes the **grounding guard**: every `Job #id` must have been returned by a tool this turn or be in
   the conversation state; otherwise the answer is replaced by a deterministic one built from the tool results.
5. Failures: LLM unreachable before any tool ran → the deterministic pipeline (3b) answers with the AI-unavailable
   notice; LLM fails after tools ran → a deterministic answer is built from the tool results.
6. The response carries `toolCalls` (`name`, raw JSON `arguments`, `summary`, `durationMs`, `ok`) — shown as 🔧 chips
   in the UI and in the RAG Inspector (`debug.toolCalls`).

### 3b. Pipeline mode (`app.chat.mode=pipeline`, and the fallback)

1. `ChatController` validates the `ChatRequest` (message 1–2000 chars, ≤ 4 selected jobs, filter ranges).
2. `ConversationService` loads/creates the conversation, stores the user message, builds a bounded history window and the
   follow-up state (previous criteria, shown jobs, focus job).
3. `CriteriaExtractionService` asks the LLM for JSON (`intent`, `standaloneQuery`, `skills`, `keywords`, `location`,
   `remote`, `experienceMin/Max`, `salaryMin/Max`, `employmentType`, `ordinals`, `jobIds`). The output is parsed
   defensively (first `{…}` block), canonicalised (skills via `SkillCatalog`, locations via `LocationNormalizer`,
   salaries in LPA converted to rupees), validated with Jakarta Validation and **merged** with the rule-based parse.
   Any failure → rule-based result only.
4. Intent routing in `ChatService`:
   - **NEW_SEARCH** — hybrid search with the extracted criteria (+ optional API `filters`).
   - **REFINE** — previous criteria merged with the new constraints, search restricted to the previous candidate ids.
   - **COMPARE** — job ids from explicit ids → ordinals on the shown list → UI selection → top 3 shown; if nothing was
     shown yet, a search runs first.
   - **JOB_QUESTION** — target from explicit id → ordinal → UI focus → conversation focus → first shown job; vector
     retrieval restricted to that job (`allowedJobIds = {id}`), top 4 chunks.
   - **GENERAL** — small talk and short career advice answered by the LLM (`PromptTemplates.SMALL_TALK_SYSTEM`, no
     job data). If the AI is down, `FallbackAnswerBuilder.smallTalk` gives a friendly greeting/help reply, the response
     is marked `aiAvailable=false` with the notice, and the error reason goes to the debug notes.
5. `RagService.buildContext` assembles the grounding context (facts + match assessment + ≤ 3 excerpts per job) and
   `generateGroundedAnswer` calls the LLM with the grounding system prompt and the last ≤ 4 user messages.
6. Grounding guard: `#id` / `Job 123` references in the answer must be a subset of the context job ids.
7. The assistant message, the new follow-up state and a `RagTrace` are stored; the `ChatResponse` returns cards,
   sources, comparison, criteria, relaxed filters, AI availability, suggestions and (in debug mode) the trace.

## 4. Hybrid search and ranking

```
criteria ──► SQL (JobSpecifications) ──► candidates ──► topic gate (skills / keywords)
query    ──► embed ──► cosine top-20 chunks among candidates ──► group by job (best chunk score)
          ──► RelevanceScorer ──► sort (score, semantic, salary ceiling, recency) ──► top 5
```

Hard SQL filters

| Criterion | Predicate |
|---|---|
| `salaryMin` | `job.salary_max >= salaryMin` (range reaches the minimum) |
| `salaryMax` | `job.salary_min <= salaryMax` |
| experience `[uMin, uMax]` | `job.experience_min <= uMax AND job.experience_max >= uMin` (overlap) |
| `location` | `lower(location) LIKE %loc%` — skipped when `remote=true` (location becomes a ranking signal) |
| `remote`, `employmentType` | equality |

Scoring (`RelevanceScorer`) — weighted average over **applicable** components only:

| Component | Weight | Value |
|---|---|---|
| semantic | 0.35 | best chunk cosine ÷ best cosine among retrieved jobs |
| skills | 0.25 | matched requested skills ÷ requested skills |
| experience | 0.15 | 1 if ranges overlap, −0.25 per year of distance |
| location | 0.10 | 1 match · 0.8 remote job when remote requested · 0 otherwise |
| salary | 0.10 | 1 range fully above minimum · 0.5 only the upper end reaches it · 0 below |
| remote | 0.05 | 1 match · 0 otherwise |

Relaxation: if nothing survives, filters are dropped cumulatively in the order salary → experience → location →
employment type → remote until something matches; results are then scored against the **original** criteria so the
gaps explain what is not met, and the response lists `relaxedFilters`. Skills/keywords are never relaxed.

## 5. Indexing

- Trigger: `ApplicationReadyEvent` (background thread `rag-indexer`, so the API is up immediately) or
  `POST /api/rag/reindex` (synchronous, returns stats). A lock prevents concurrent builds.
- Snapshot reuse requires: same dataset hash **and** same embedding provider/model as the preferred provider.
- Embedding: batches of 32. Retries (1 s, 4 s back-off) apply only to retryable failures (408/429/5xx/network).
- **Rate limits (429 / `RESOURCE_EXHAUSTED`)** are waited out rather than counted as failures, up to 20 waits per batch.
  The wait uses Gemini's "retry in Ns" hint, falling back to 30 s. On the free tier (~100 embeddings/min) the first
  600-chunk build takes about 6 minutes. Meanwhile, queries use keyword retrieval.
- In `auto` mode, if remote embeddings still fail, the whole index is built with the local embedder and the reason is
  recorded in the status.
- The snapshot (`db/vector-store.bin`) means the expensive build happens once, and again only when the data or the
  embedding model changes.
- The index is swapped atomically (copy-on-write), so searches never observe a half-built index.

## 6. Failure handling

| Failure | Behaviour |
|---|---|
| Agent LLM fails before any tool call | Deterministic pipeline answers; notice banner |
| Agent LLM fails after tools ran | Deterministic answer from the tool results |
| Model passes invalid tool arguments / unknown job | Tool returns `{"error": …}` to the model, chip marked failed |
| No `GEMINI_API_KEY` | Local embeddings, rule-based extraction, deterministic answers; UI shows "AI offline · search-only mode" |
| Provider 401/403/400 | Not retried; same fallback; `/api/system/status.aiStatus = UNAVAILABLE` with the last error |
| Rate limit / 5xx / timeout at chat time | One retry per model, then the next fallback model; if all fail → deterministic fallback for that turn with the notice banner |
| Rate limit during indexing | Waited out (Gemini "retry in Ns" hint), then continues |
| Invalid LLM JSON | Rule-based extraction (trace note) |
| LLM mentions unknown jobs | Grounding guard discards the answer → deterministic answer |
| Query embedding fails | Keyword retrieval over the same chunks (`retrievalMode = keyword`) |
| No results | Relaxation, else an honest "couldn't find" message — the LLM is not called |
| Bad request / unknown job | 400 with `fieldErrors` / 404, always JSON `ErrorResponse` |

## 7. Security

- The key lives only in the backend: `GEMINI_API_KEY` in a git-ignored `.env` file or the environment. No key is
  hardcoded in `application.properties`. `OpenRouterProperties.toString()` masks it, and no DTO contains it
  (verified by `SecurityConfigurationTest`).
- CORS is limited to the Vite dev origins; in development the Vite proxy makes calls same-origin anyway.
- The frontend renders LLM text through a small safe Markdown renderer that builds React nodes (no raw HTML injection).
- There is no authentication: conversations and saved jobs are shared by everyone using the same backend.

## 8. Frontend

React 19 + TypeScript + Vite, with no UI framework.

| Area | Pieces |
|---|---|
| Shell | `App` (layout, header with conversation title and menu), `Sidebar` (history grouped by date, search, rename/delete), settings dialog (theme, RAG Inspector, clear all, model/index info), light/dark/system theme via `data-theme` on `<html>` |
| Chat | `ChatWindow` (welcome screen with suggestion cards), `ChatMessage` (copy, regenerate, edit & resend, thumbs up/down), `ChatInput` (auto-grow, Send ↔ Stop) |
| State | `useChat`: send / stop (`AbortController`) / regenerate / editAndResend / setFeedback / openConversation / exportMarkdown. Restored history re-fetches job cards via `POST /api/jobs/cards`. |
| Jobs | `JobCard`, `JobDetails` drawer, `JobComparison`, compare bar, saved jobs (`useSavedJobs` store + `SavedJobsPanel`) |
| Feedback | toasts (`ToastProvider` / `useToast`) |
| Shortcuts | `Ctrl/Cmd+Shift+O` new chat · `Ctrl/Cmd+K` search chats · `/` focus input · `Ctrl/Cmd+Shift+S` toggle sidebar · `Esc` stop |
