import { useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import type { ChatResponse, Feedback, UiMessage } from '../types';
import { formatTime } from '../utils/format';
import { Markdown } from '../utils/markdown';
import { useChatActions } from './ChatActionsContext';
import { JobActionsContext, useJobActions } from './JobActionsContext';
import { JobComparison } from './JobComparison';
import { JobList } from './JobList';
import { SuggestedQuestions } from './SuggestedQuestions';
import '../chat-features.css';

const INTENT_LABELS: Record<string, string> = {
  NEW_SEARCH: 'Search',
  REFINE: 'Refined search',
  COMPARE: 'Comparison',
  JOB_QUESTION: 'Job question',
  GENERAL: 'General',
};

const TOOL_LABELS: Record<string, string> = {
  search_jobs: 'Searched jobs',
  get_job_details: 'Read job listing',
  compare_jobs: 'Compared jobs',
};

function describeArgs(raw: string): string {
  try {
    const args = JSON.parse(raw) as Record<string, unknown>;
    return Object.entries(args)
      .filter(([, v]) => v !== null && v !== '' && !(Array.isArray(v) && v.length === 0))
      .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : String(v)}`)
      .join(' · ');
  } catch {
    return raw;
  }
}

/* ---------- icons ---------- */
const Icon = ({ children }: { children: ReactNode }) => (
  <svg
    width="15"
    height="15"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    {children}
  </svg>
);
const CopyIcon = () => (
  <Icon>
    <rect x="9" y="9" width="12" height="12" rx="2" />
    <path d="M5 15V5a2 2 0 0 1 2-2h8" />
  </Icon>
);
const EditIcon = () => (
  <Icon>
    <path d="M12 20h9" />
    <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
  </Icon>
);
const RegenIcon = () => (
  <Icon>
    <path d="M21 12a9 9 0 1 1-3-6.7L21 8" />
    <path d="M21 3v5h-5" />
  </Icon>
);
const ThumbUp = ({ filled }: { filled: boolean }) => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
    <path d="M7 10v11H3V10h4Zm0 0 4-8a3 3 0 0 1 3 3v4h5.5a2 2 0 0 1 2 2.3l-1.4 8A2 2 0 0 1 18.1 21H7" />
  </svg>
);
const ThumbDown = ({ filled }: { filled: boolean }) => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
    <path d="M17 14V3h4v11h-4Zm0 0-4 8a3 3 0 0 1-3-3v-4H4.5a2 2 0 0 1-2-2.3l1.4-8A2 2 0 0 1 5.9 3H17" />
  </svg>
);

function ActionButton({
  label,
  onClick,
  active,
  disabled,
  children,
}: {
  label: string;
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      className={`msg-action${active ? ' active' : ''}`}
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      aria-pressed={active}
      data-tip={label}
    >
      {children}
    </button>
  );
}

function AssistantActions({ uiId, text, feedback }: { uiId: string; text: string; feedback: Feedback | undefined }) {
  const actions = useChatActions();
  if (!actions) return null;
  const isLast = actions.lastAssistantId === uiId;
  const rate = (value: 'up' | 'down') => actions.setFeedback(uiId, feedback === value ? null : value);
  return (
    <div className={`msg-actions${isLast ? ' pinned' : ''}`} role="toolbar" aria-label="Message actions">
      <ActionButton label="Copy" onClick={() => actions.copyText(text)}>
        <CopyIcon />
      </ActionButton>
      <ActionButton label="Good response" active={feedback === 'up'} onClick={() => rate('up')}>
        <ThumbUp filled={feedback === 'up'} />
      </ActionButton>
      <ActionButton label="Bad response" active={feedback === 'down'} onClick={() => rate('down')}>
        <ThumbDown filled={feedback === 'down'} />
      </ActionButton>
      {isLast && (
        <ActionButton label="Regenerate" onClick={actions.regenerate} disabled={actions.loading}>
          <RegenIcon />
        </ActionButton>
      )}
    </div>
  );
}

function UserMessage({ uiId, text, timestamp }: { uiId: string; text: string; timestamp: string }) {
  const actions = useChatActions();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(text);
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!editing || !el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 240)}px`;
  }, [draft, editing]);

  useEffect(() => {
    if (!editing) return;
    const el = ref.current;
    el?.focus();
    el?.setSelectionRange(el.value.length, el.value.length);
  }, [editing]);

  const cancel = () => {
    setEditing(false);
    setDraft(text);
  };

  const save = () => {
    const value = draft.trim();
    if (!value || !actions) return;
    setEditing(false);
    if (value !== text) actions.editAndResend(uiId, value);
  };

  if (editing) {
    return (
      <div className="msg user">
        <div className="msg-col editing">
          <div className="edit-box">
            <textarea
              ref={ref}
              value={draft}
              maxLength={2000}
              aria-label="Edit message"
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  save();
                }
                if (e.key === 'Escape') {
                  e.stopPropagation();
                  cancel();
                }
              }}
            />
            <div className="edit-actions">
              <span className="edit-hint">Editing resends from here — later messages are replaced.</span>
              <button type="button" className="btn btn-ghost btn-sm" onClick={cancel}>
                Cancel
              </button>
              <button type="button" className="btn btn-primary btn-sm" onClick={save} disabled={!draft.trim()}>
                Save &amp; send
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="msg user">
      <div className="msg-col">
        <div className="bubble">
          <p className="plain">{text}</p>
          <time className="msg-time">{formatTime(timestamp)}</time>
        </div>
        {actions && (
          <div className="msg-actions" role="toolbar" aria-label="Message actions">
            <ActionButton label="Copy" onClick={() => actions.copyText(text)}>
              <CopyIcon />
            </ActionButton>
            <ActionButton
              label="Edit"
              disabled={actions.loading}
              onClick={() => {
                setDraft(text);
                setEditing(true);
              }}
            >
              <EditIcon />
            </ActionButton>
          </div>
        )}
      </div>
    </div>
  );
}

function AssistantBody({ res }: { res: ChatResponse }) {
  const { openDetails, sendSuggestion } = useJobActions();
  const toolCalls = res.toolCalls ?? [];
  const smallTalk = res.intent === 'GENERAL' && res.jobs.length === 0;
  return (
    <>
      {!smallTalk && (
        <div className="msg-tags">
          <span className="tag">{INTENT_LABELS[res.intent] ?? res.intent}</span>
          <span className={`tag ${res.aiAvailable ? 'tag-ai' : 'tag-search'}`}>
            {res.aiAvailable ? 'AI · grounded' : 'Search-only'}
          </span>
        </div>
      )}

      {toolCalls.length > 0 && (
        <div className="tool-calls" aria-label="Backend tools called">
          {toolCalls.map((tc, i) => (
            <span
              key={i}
              className={`tool-chip${tc.ok ? '' : ' failed'}`}
              title={`${tc.name}(${describeArgs(tc.arguments)}) — ${tc.durationMs} ms`}
            >
              <span aria-hidden="true">🔧</span> {TOOL_LABELS[tc.name] ?? tc.name}
              <span className="tool-summary"> · {tc.summary}</span>
            </span>
          ))}
        </div>
      )}

      {res.notice && (
        <div className="notice" role="alert">
          <span aria-hidden="true">⚠</span> {res.notice}
        </div>
      )}

      <div className="answer-text">
        <Markdown text={res.message} onJobRef={openDetails} />
      </div>

      {res.relaxedFilters.length > 0 && (
        <p className="relaxed-note">
          No exact matches — relaxed: <strong>{res.relaxedFilters.join(', ')}</strong>
        </p>
      )}

      {res.comparison ? <JobComparison comparison={res.comparison} /> : <JobList jobs={res.jobs} totalMatches={res.totalMatches} />}

      {res.sources.length > 0 && (
        <div className="sources" aria-label="Sources">
          <span className="sources-label">Sources:</span>
          {res.sources.map((s) => (
            <button
              key={s.jobId}
              type="button"
              className="source-chip"
              onClick={() => openDetails(s.jobId)}
              title={`${s.title} · ${s.company}${s.sections.length ? ` — sections: ${s.sections.join(', ')}` : ''}${
                s.similarity !== null ? ` — similarity ${s.similarity.toFixed(3)}` : ''
              }`}
            >
              [Job #{s.jobId}]
            </button>
          ))}
        </div>
      )}

      {res.suggestions.length > 0 && <SuggestedQuestions questions={res.suggestions} onPick={sendSuggestion} compact />}
    </>
  );
}

function AssistantShell({ wide, children, actions }: { wide?: boolean; children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="msg assistant">
      <div className="avatar" aria-hidden="true">
        🤖
      </div>
      <div className={`msg-col${wide ? ' wide' : ''}`}>
        <div className="bubble">{children}</div>
        {actions}
      </div>
    </div>
  );
}

interface Props {
  message: UiMessage;
  onRetry: (errorId: string, retryMessage: string) => void;
}

export function ChatMessage({ message, onRetry }: Props) {
  const jobActions = useContext(JobActionsContext);

  switch (message.kind) {
    case 'user':
      return <UserMessage uiId={message.id} text={message.text} timestamp={message.timestamp} />;

    case 'history':
      if (message.role === 'USER') {
        return <UserMessage uiId={message.id} text={message.text} timestamp={message.timestamp} />;
      }
      return (
        <AssistantShell
          wide={!!message.jobs?.length}
          actions={<AssistantActions uiId={message.id} text={message.text} feedback={message.feedback} />}
        >
          <div className="answer-text">
            <Markdown text={message.text} onJobRef={jobActions?.openDetails} />
          </div>
          {message.jobs && message.jobs.length > 0 && <JobList jobs={message.jobs} />}
          <time className="msg-time">{formatTime(message.timestamp)}</time>
        </AssistantShell>
      );

    case 'assistant':
      return (
        <AssistantShell
          wide
          actions={<AssistantActions uiId={message.id} text={message.response.message} feedback={message.feedback} />}
        >
          <AssistantBody res={message.response} />
          <time className="msg-time">{formatTime(message.response.timestamp)}</time>
        </AssistantShell>
      );

    case 'comparison':
      return (
        <AssistantShell wide>
          <JobComparison comparison={message.comparison} />
        </AssistantShell>
      );

    case 'stopped':
      return (
        <div className="stopped-note" role="status">
          <span className="stopped-dot" aria-hidden="true" /> Response stopped
        </div>
      );

    case 'error':
      return (
        <div className="msg assistant">
          <div className="avatar error" aria-hidden="true">
            !
          </div>
          <div className="bubble error-bubble" role="alert">
            <p>{message.text}</p>
            {message.retryMessage && (
              <button type="button" className="btn btn-sm btn-ghost" onClick={() => onRetry(message.id, message.retryMessage!)}>
                ↻ Retry
              </button>
            )}
          </div>
        </div>
      );
  }
}
