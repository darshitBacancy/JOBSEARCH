# REST API

Base URL: `http://localhost:9091` (through the Vite dev server: `http://localhost:4000/api/...`).
All errors use the same JSON shape:

```json
{ "timestamp": "2026-09-25T08:00:00Z", "status": 400, "error": "Bad Request",
  "message": "Request validation failed", "fieldErrors": { "message": "message must not be blank" } }
```

---

## POST /api/chat

One conversational turn.

```json
{
  "conversationId": null,
  "message": "Find remote Java Spring Boot jobs above 15 LPA",
  "selectedJobIds": null,
  "focusJobId": null,
  "filters": { "remote": null, "location": null, "salaryMin": null, "experienceMin": null, "experienceMax": null, "employmentType": null },
  "debug": true
}
```

| Field | Notes |
|---|---|
| `conversationId` | `null` starts a conversation; reuse the returned id for follow-ups |
| `message` | 1–2000 characters |
| `selectedJobIds` | up to 4 ids ticked in the UI (used by "compare these") |
| `focusJobId` | job the user clicked "Ask about this job" on ("this job") |
| `filters` | optional explicit filters (the current UI no longer sends them); override values extracted from the message. `salaryMin` is annual INR |
| `debug` | include the RAG trace (`rag.debug-enabled` must be true) |

Response (trimmed, real output in search-only mode):

```json
{
  "conversationId": "5c197aef-bc2f-4c50-8c6a-0b5d50213791",
  "messageId": 68,
  "message": "I found 9 jobs matching your request (Java, Spring Boot · remote · ≥ ₹15 LPA). Top 5:\n\n1. **Lead Java Engineer** - Suraksha Digital Insurance Tech (Job #118)\n ...",
  "intent": "NEW_SEARCH",
  "jobs": [
    {
      "id": 118, "title": "Lead Java Engineer", "company": "Suraksha Digital Insurance Tech",
      "location": "Remote, India", "remote": true,
      "employmentType": "FULL_TIME", "employmentTypeLabel": "Full-time",
      "experience": "7-12 years", "experienceMin": 7, "experienceMax": 12,
      "salary": "₹29-37 LPA", "salaryMin": 2900000, "salaryMax": 3700000, "currency": "INR",
      "skills": ["Java", "Spring Boot", "AWS", "Microservices", "PostgreSQL", "Hibernate"],
      "postedDate": "2026-07-30",
      "matchScore": 100, "matchLabel": "Strong match",
      "matchReasons": [
        "Job content closely matches your request (skills, responsibilities)",
        "Java and Spring Boot match",
        "Remote preference matches",
        "Salary ₹29-37 LPA meets your ₹15 LPA minimum"
      ],
      "gaps": [],
      "scoreBreakdown": { "semantic": 1.0, "skills": 1.0, "experience": null, "location": null, "remote": 1.0, "salary": 1.0 }
    }
  ],
  "totalMatches": 9,
  "sources": [
    { "jobId": 118, "title": "Lead Java Engineer", "company": "Suraksha Digital Insurance Tech", "sections": ["SKILLS", "RESPONSIBILITIES"], "similarity": 0.43 }
  ],
  "comparison": null,
  "criteria": { "keywords": [], "skills": ["Java", "Spring Boot"], "location": null, "remote": true,
                "experienceMin": null, "experienceMax": null, "salaryMin": 1500000, "salaryMax": null, "employmentType": null },
  "relaxedFilters": [],
  "aiAvailable": false,
  "notice": "AI assistance is temporarily unavailable. I can still search the available jobs using your filters.",
  "focusJobId": null,
  "suggestions": ["Compare the first three jobs", "Only full-time roles", "What skills does the first job require?"],
  "toolCalls": [],
  "userMessageId": 67,
  "assistantMessageId": 68,
  "debug": {
    "traceId": "14ce3a14-06d2-40d1-a0de-9bcbbed83823",
    "userQuery": "Find remote Java Spring Boot jobs above 15 LPA",
    "intent": "NEW_SEARCH",
    "criteriaSource": "rules",
    "criteria": { "...": "..." },
    "embeddingProvider": "local", "embeddingModel": "local-hashing-bow-1024", "retrievalMode": "vector",
    "structuredCandidateCount": 41,
    "retrievedChunks": [ { "chunkId": "job-118-skills-0", "jobId": 118, "section": "SKILLS", "similarity": 0.43, "preview": "Job: Lead Java Engineer at ..." } ],
    "selectedJobs": [ { "jobId": 118, "title": "Lead Java Engineer", "company": "...", "score": 1.0, "breakdown": { "semantic": 1.0, "skills": 1.0 } } ],
    "relaxedFilters": [],
    "contextSentToLlm": "JOB CONTEXT:\n\n[JOB #118]\nTitle: Lead Java Engineer\n...",
    "llmModel": "gemini-flash-latest",
    "answerSource": "fallback",
    "finalAnswer": "I found 9 jobs ...",
    "timingsMs": { "understanding": 3, "retrieval+ranking": 9, "generation": 0, "total": 21 },
    "notes": ["LLM not configured: used rule-based extraction", "LLM not configured"]
  },
  "timestamp": "2026-09-25T08:11:07.740Z"
}
```

`toolCalls` lists the backend tools the agent (default `app.chat.mode=agent`) called during the turn; it is empty for
small talk and in pipeline/fallback mode (as in the example above). Example from an agent turn:

```json
"toolCalls": [
  { "name": "search_jobs",
    "arguments": "{\"query\":\"remote Java Spring Boot jobs\",\"skills\":[\"Java\",\"Spring Boot\"],\"remote\":true,\"min_salary_lpa\":12}",
    "summary": "10 match(es), showing 5", "durationMs": 42, "ok": true }
]
```

Tools: `search_jobs` (hybrid SQL + vector search), `get_job_details` (listing + vector-retrieved excerpts),
`compare_jobs` (2-4 ids). The same list appears in `debug.toolCalls`.

`intent` is one of `NEW_SEARCH`, `REFINE`, `COMPARE`, `JOB_QUESTION`, `GENERAL`. For `COMPARE`, `comparison` is filled
(same shape as `POST /api/jobs/compare`). `scoreBreakdown` values are `0..1`, or `null` when the component does not
apply to the query.

`userMessageId` / `assistantMessageId` are the stored `chat_message` ids of this turn. The UI uses them for
feedback (`PUT /api/messages/{id}/feedback`) and edit & resend (`POST /api/conversations/{id}/truncate`).

Greetings, small talk and general career questions ("hi", "how do I prepare for an interview?") are answered directly
by the model: `intent` is `GENERAL`, and `jobs` and `toolCalls` are empty.

## POST /api/chat/regenerate

Re-answers the last question of a conversation. The conversation's last `ASSISTANT` message and the `USER` message
before it are deleted, then that user message is run through the same flow as `POST /api/chat`.

```json
{ "conversationId": "5c197aef-bc2f-4c50-8c6a-0b5d50213791", "debug": false }
```

→ a `ChatResponse` (same shape as `/api/chat`, with new `userMessageId` / `assistantMessageId`).
`404` for an unknown conversation, `400` when it has no user message.

## GET /api/conversations

Sidebar list: conversations that have at least one message, most recently active first. `title` is taken from the first
user message (max 60 chars) unless the conversation was renamed.

```json
[
  { "id": "5c197aef-bc2f-4c50-8c6a-0b5d50213791", "title": "Find remote Java Spring Boot jobs above 15 LPA",
    "createdAt": "2026-09-25T08:10:59.120Z", "updatedAt": "2026-09-25T08:14:02.500Z", "messageCount": 6 }
]
```

## PUT /api/conversations/{id}

```json
{ "title": "Remote Java roles" }
```

→ `204`. Blank title → `400`; unknown conversation → `404`.

## DELETE /api/conversations/{id}

Deletes the conversation and all its messages → `204`; unknown → `404`.

## DELETE /api/conversations

Deletes **all** conversations and messages ("Clear all conversations" in Settings) → `204`.

## POST /api/conversations/{id}/truncate

Used by "edit & resend": deletes the given message and every later message of the conversation. The UI then sends
the edited text with `POST /api/chat`.

```json
{ "fromMessageId": 123 }
```

→ `204`. Unknown conversation or message → `404`.

## PUT /api/messages/{id}/feedback

Thumbs up / down on an assistant message; `null` clears it.

```json
{ "rating": "up" }
```

→ `204`. A value other than `"up"`, `"down"` or `null` → `400`; unknown message → `404`.

## POST /api/jobs/search

Deterministic hybrid search without the LLM (free text is parsed by the rule-based parser).

```json
{ "query": "remote Java jobs", "skills": ["AWS"], "location": null, "remote": null,
  "experienceMin": null, "experienceMax": null, "salaryMin": 1500000, "employmentType": null, "limit": 10 }
```

→ `{ "jobs": [JobCard…], "totalMatches": 7, "criteria": {…}, "relaxedFilters": [], "retrievalMode": "vector" }`

## GET /api/jobs/{id}

→ Job card fields plus `description`, `requirements[]`, `responsibilities[]`, `benefits[]`, `applicationUrl`,
`dataNotice`. Unknown id → `404`.

## POST /api/jobs/compare

```json
{ "jobIds": [101, 102, 103] }
```

→
```json
{
  "jobs": [JobCard, JobCard, JobCard],
  "rows": [
    { "label": "Title", "values": ["…", "…", "…"] },
    { "label": "Company", "values": ["…", "…", "…"] },
    { "label": "Location", "values": ["…"] }, { "label": "Remote", "values": ["…"] },
    { "label": "Experience", "values": ["…"] }, { "label": "Salary", "values": ["…"] },
    { "label": "Skills", "values": ["…"] }, { "label": "Employment", "values": ["…"] },
    { "label": "Posted", "values": ["…"] }, { "label": "Benefits", "values": ["…"] }
  ],
  "summary": "- Highest salary ceiling: Job #103 (…)\n- Lowest experience requirement: …"
}
```

2–4 ids required (400 otherwise); unknown id → 404. The summary is written by the LLM from the jobs' data, or
computed deterministically when the LLM is unavailable.

## POST /api/jobs/cards

Job cards for a list of ids, in the requested order (unknown ids are skipped, max 50). The UI uses it to restore the
job cards of earlier answers when a conversation is reopened.

```json
{ "jobIds": [118, 112, 129] }
```

→ `[JobCard, JobCard, JobCard]` (same card shape as in chat responses).

## Saved jobs

Bookmarked jobs. There is no authentication, so the list is global, like conversations.

| Request | Response |
|---|---|
| `GET /api/saved-jobs` | `[JobCard…]`, most recently saved first |
| `PUT /api/saved-jobs/{jobId}` | `204` (idempotent); unknown job → `404` |
| `DELETE /api/saved-jobs/{jobId}` | `204` (idempotent) |

## GET /api/jobs?page=0&size=20

→ `{ "content": [JobCard…], "page": 0, "size": 20, "totalElements": 120, "totalPages": 6 }` (newest first).

## POST /api/rag/reindex

Reloads `data/jobs.json`, rebuilds all chunks and embeddings, replaces the in-memory index and rewrites the snapshot.

Real output with the local embedder. With a Gemini key, `embeddingProvider` is `openrouter` (the id of the remote
OpenAI-compatible provider), `embeddingModel` is `gemini-embedding-001` and `dimensions` is `3072`. A free-tier Gemini
build takes about 6 minutes because of the embeddings quota; the call blocks until it is done.

```json
{ "indexed": true, "jobCount": 120, "chunkCount": 600, "embeddingProvider": "local",
  "embeddingModel": "local-hashing-bow-1024", "dimensions": 1024, "indexedAt": "2026-09-25T08:09:36.380Z", "durationMs": 122,
  "message": "READY: Indexed 600 chunks from 120 jobs with local (local-hashing-bow-1024) in 122 ms" }
```

## GET /api/rag/status

Same shape as above (without `durationMs`).

## GET /api/rag/retrieve?q=kubernetes%20terraform&topK=5

Raw vector retrieval over the whole index, for demos/debugging:

```json
{ "query": "kubernetes terraform", "mode": "vector", "embeddingProvider": "local", "embeddingModel": "local-hashing-bow-1024",
  "results": [ { "chunkId": "job-170-skills-0", "jobId": 170, "section": "SKILLS", "similarity": 0.303,
                 "metadata": { "jobId": "170", "title": "…", "company": "…", "location": "…", "skills": "…", "remote": "true", "section": "SKILLS" },
                 "text": "Job: … Skills: Kubernetes, Docker, AWS, Terraform, …" } ] }
```

## GET /api/rag/traces?limit=20 · GET /api/rag/traces/{traceId}

The most recent RAG traces (same shape as `debug` above). Returns `403` when `rag.debug-enabled=false`.

## GET /api/conversations/{id}/messages

→ `[ { "id": 1, "role": "USER", "message": "…", "jobIds": [], "feedback": null, "timestamp": "…" }, { "id": 2, "role": "ASSISTANT", "jobIds": [118, 112], "feedback": "up", … } ]`

`feedback` is `"up"`, `"down"` or `null`. Unknown conversation → `404`.

## GET /api/system/status

```json
{ "aiConfigured": true, "aiStatus": "ONLINE", "aiLastError": null,
  "chatModel": "gemini-flash-latest",
  "embeddingProvider": "openrouter", "embeddingModel": "gemini-embedding-001",
  "vectorStore": "in-memory (snapshot: ./db/vector-store.bin)", "debugEnabled": true,
  "index": { "indexed": true, "jobCount": 120, "chunkCount": 600, "embeddingModel": "gemini-embedding-001", "dimensions": 3072, "…": "…" } }
```

`chatModel` is the model that answered the most recent call, so it shows a fallback model (e.g. `gemini-3.5-flash`)
while the primary is overloaded.

`aiStatus`: `NOT_CONFIGURED` (no key), `UNKNOWN` (key set, no call yet), `ONLINE` (last call succeeded),
`UNAVAILABLE` (last call failed; `aiLastError` explains why). The key itself is never returned.
