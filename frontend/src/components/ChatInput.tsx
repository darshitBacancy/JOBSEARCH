import { useEffect, useRef, type FormEvent, type KeyboardEvent } from 'react';

export const MAX_MESSAGE_LENGTH = 2000;
const COUNTER_FROM = MAX_MESSAGE_LENGTH - 300;

interface Props {
  value: string;
  onChange: (value: string) => void;
  onSend: (value: string) => void;
  /** Blocks sending (e.g. while an answer or a conversation is loading). */
  disabled: boolean;
  /** An answer is being generated: shows the Stop button when `onStop` is given. */
  loading?: boolean;
  onStop?: () => void;
  focusLabel?: string | null;
  onClearFocus?: () => void;
  focusToken?: number;
  placeholder?: string;
}

export function ChatInput({
  value,
  onChange,
  onSend,
  disabled,
  loading = false,
  onStop,
  focusLabel,
  onClearFocus,
  focusToken,
  placeholder = 'Ask about jobs, skills, salaries… or just say hi',
}: Props) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const tooLong = value.length > MAX_MESSAGE_LENGTH;
  const canSend = !disabled && value.trim().length > 0 && !tooLong;
  const canStop = loading && !!onStop;

  // Auto-grow the textarea.
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, [value]);

  // Focus when something (e.g. "Ask about this job") prefills the input.
  useEffect(() => {
    if (focusToken) ref.current?.focus();
  }, [focusToken]);

  // Esc stops generation from anywhere, unless a dialog/drawer is open (it owns Esc then).
  useEffect(() => {
    if (!canStop) return;
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented || document.body.classList.contains('no-scroll')) return;
      if (document.querySelector('[aria-modal="true"], .saved-panel.open')) return;
      onStop!();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [canStop, onStop]);

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (canStop) {
      onStop!();
      return;
    }
    if (!canSend) return;
    onSend(value);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (!canStop) submit();
    }
  };

  return (
    <form className="chat-input" onSubmit={submit}>
      {focusLabel && (
        <div className="focus-chip">
          <span>
            Asking about <strong>{focusLabel}</strong>
          </span>
          {onClearFocus && (
            <button type="button" className="icon-btn" onClick={onClearFocus} aria-label="Stop focusing on this job">
              ✕
            </button>
          )}
        </div>
      )}
      <div className={`chat-input-row composer${disabled && !loading ? ' is-disabled' : ''}`}>
        <textarea
          ref={ref}
          value={value}
          rows={1}
          placeholder={placeholder}
          aria-label="Message"
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          maxLength={MAX_MESSAGE_LENGTH + 200}
          disabled={disabled && !loading}
        />
        {canStop ? (
          <button type="submit" className="send-btn round stop" aria-label="Stop generating" title="Stop generating (Esc)">
            <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="5" y="5" width="14" height="14" rx="2.5" fill="currentColor" />
            </svg>
          </button>
        ) : (
          <button
            type="submit"
            className="send-btn round"
            disabled={!canSend}
            aria-label="Send message"
            title="Send (Enter)"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 19V5" />
              <path d="m5 12 7-7 7 7" />
            </svg>
          </button>
        )}
      </div>
      <div className="chat-input-meta">
        <span>{canStop ? 'Generating… press Esc to stop' : 'Enter to send · Shift+Enter for a new line'}</span>
        {value.length >= COUNTER_FROM && (
          <span className={tooLong ? 'over' : 'near'}>
            {value.length}/{MAX_MESSAGE_LENGTH}
          </span>
        )}
      </div>
    </form>
  );
}
