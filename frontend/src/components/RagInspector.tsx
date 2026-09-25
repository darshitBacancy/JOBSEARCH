import type { ReactNode } from 'react';
import type { RagTrace } from '../types';
import { SCORE_LABELS, pct } from '../utils/format';

interface Props {
  trace: RagTrace | null;
  onClose: () => void;
}

function Step({ n, title, children, meta }: { n: number; title: string; children: ReactNode; meta?: ReactNode }) {
  return (
    <details className="insp-step" open={n <= 4}>
      <summary>
        <span className="insp-n">{n}</span>
        <span className="insp-title">{title}</span>
        {meta && <span className="insp-meta">{meta}</span>}
      </summary>
      <div className="insp-body">{children}</div>
    </details>
  );
}

export function RagInspector({ trace, onClose }: Props) {
  return (
    <aside className="inspector" aria-label="RAG Inspector">
      <div className="inspector-head">
        <div>
          <h2>RAG Inspector</h2>
          <p>How the latest answer was produced</p>
        </div>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Close RAG Inspector">
          ✕
        </button>
      </div>

      {!trace ? (
        <p className="insp-empty">
          Send a message with the inspector on to see the pipeline: query → extracted criteria → retrieved chunks → ranking →
          LLM context → answer.
        </p>
      ) : (
        <div className="inspector-body">
          <Step n={1} title="User query" meta={trace.intent}>
            <p className="insp-query">“{trace.userQuery}”</p>
          </Step>

          {(trace.toolCalls ?? []).length > 0 && (
            <Step n={1} title="Tools called by the LLM" meta={`${trace.toolCalls!.length} call(s)`}>
              <ul className="insp-notes">
                {trace.toolCalls!.map((tc, i) => (
                  <li key={i}>
                    <code>{tc.name}</code> <span className="insp-small">{tc.arguments}</span> → {tc.summary} ({tc.durationMs} ms)
                  </li>
                ))}
              </ul>
            </Step>
          )}

          <Step n={2} title="Extracted criteria" meta={`source: ${trace.criteriaSource}`}>
            <pre className="code">{JSON.stringify(trace.criteria, null, 2)}</pre>
            <p className="insp-small">Structured candidates after DB filtering: {trace.structuredCandidateCount}</p>
            {trace.relaxedFilters.length > 0 && (
              <p className="insp-small warn">Relaxed filters: {trace.relaxedFilters.join(', ')}</p>
            )}
          </Step>

          <Step
            n={3}
            title="Retrieved chunks"
            meta={`${trace.retrievedChunks.length} · ${trace.embeddingProvider}/${trace.embeddingModel}`}
          >
            {trace.retrievedChunks.length === 0 ? (
              <p className="insp-small">No chunks retrieved.</p>
            ) : (
              <div className="table-scroll">
                <table className="insp-table">
                  <thead>
                    <tr>
                      <th>Job</th>
                      <th>Section</th>
                      <th>Similarity</th>
                      <th>Preview</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trace.retrievedChunks.map((c) => (
                      <tr key={c.chunkId}>
                        <td>#{c.jobId}</td>
                        <td>{c.section}</td>
                        <td>
                          <span className="sim">
                            <span className="bar">
                              <span style={{ width: `${Math.max(0, Math.min(1, c.similarity)) * 100}%` }} />
                            </span>
                            {c.similarity.toFixed(3)}
                          </span>
                        </td>
                        <td className="preview">{c.preview}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Step>

          <Step n={4} title="Selected jobs (combined ranking)" meta={`${trace.selectedJobs.length}`}>
            {trace.selectedJobs.length === 0 ? (
              <p className="insp-small">No jobs selected.</p>
            ) : (
              <ol className="insp-jobs">
                {trace.selectedJobs.map((j) => (
                  <li key={j.jobId}>
                    <div className="insp-job-head">
                      <strong>
                        #{j.jobId} {j.title}
                      </strong>{' '}
                      <span className="muted">· {j.company}</span>
                      <span className="insp-score">{Math.round(j.score)}%</span>
                    </div>
                    <div className="insp-breakdown">
                      {Object.entries(j.breakdown).map(([k, v]) => (
                        <span key={k} className={v === null ? 'na' : ''}>
                          {SCORE_LABELS[k] ?? k}: {pct(v)}
                        </span>
                      ))}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </Step>

          <Step n={5} title="Context sent to LLM" meta={trace.llmModel ?? 'not sent'}>
            {trace.contextSentToLlm ? (
              <pre className="code context">{trace.contextSentToLlm}</pre>
            ) : (
              <p className="insp-small">No LLM call was made for this answer.</p>
            )}
          </Step>

          <Step n={6} title="Final answer" meta={`source: ${trace.answerSource}`}>
            <pre className="code answer">{trace.finalAnswer}</pre>
            {Object.keys(trace.timingsMs).length > 0 && (
              <div className="insp-timings">
                {Object.entries(trace.timingsMs).map(([k, v]) => (
                  <span key={k}>
                    {k}: <strong>{v} ms</strong>
                  </span>
                ))}
              </div>
            )}
            {trace.notes.length > 0 && (
              <ul className="insp-notes">
                {trace.notes.map((n, i) => (
                  <li key={i}>{n}</li>
                ))}
              </ul>
            )}
            <p className="insp-small muted">Trace {trace.traceId}</p>
          </Step>
        </div>
      )}
    </aside>
  );
}
