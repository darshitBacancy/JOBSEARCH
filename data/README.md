# Demo job dataset

`jobs.json` is the knowledge base for the Job Search Assistant. It holds **120 job postings** (ids 101–220).

> **All of this data is made up.** The jobs, companies, salaries and application links were invented for demonstration. None of them are real or current vacancies. Every `applicationUrl` points to `https://example.com/demo-jobs/<id>`.

## Contents

- **Role families:** Java/Spring Boot (24), React/frontend (17), full-stack (11), DevOps/Cloud/SRE (12), data engineering (8), ML/GenAI (8), mobile (8), Python (7), Node.js (6), QA automation (6), Go/Rust/.NET (6), and other roles (7): design, security, Salesforce, BI and SAP.
- **Locations:** Indian cities or `Remote, India`. 45 jobs are remote or remote-friendly.
- **Salaries:** annual amounts in INR (`1200000` = ₹12 LPA), rounded to ₹50,000. Each salary range fits the seniority of the role.
- **Skills:** each job lists 4–8 skills, spelled exactly as in the controlled vocabulary in `tools/generate-jobs.mjs` (`ALLOWED_SKILLS`).

## Regenerating

```bash
node data/tools/generate-jobs.mjs
```

The generator uses a seeded PRNG, so it produces byte-identical output every time it runs. It also validates the result (ids, required keys, salary and experience ranges, allowed skills) and prints summary statistics.

If you change this file after the backend has indexed it, call `POST /api/rag/reindex` to rebuild the embeddings. The backend also detects a changed dataset hash on its next start and re-indexes automatically.

With a free-tier Gemini key, re-embedding all 600 chunks (`gemini-embedding-001`) takes about 6 minutes because of the embeddings quota. The indexer waits out rate limits on its own, and keyword retrieval is used until the new index is ready.
