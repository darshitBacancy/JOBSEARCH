import { useEffect, useState } from 'react';

// Client-side only: the backend does not stream progress, so these are honest, generic stages.
const STAGES = [
  { after: 0, text: 'Thinking…' },
  { after: 2500, text: 'Searching jobs…' },
  { after: 7000, text: 'Still working on it…' },
  { after: 15000, text: 'Taking longer than usual — the AI service may be busy…' },
];

export function LoadingIndicator({ label }: { label?: string }) {
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (label) return;
    const started = Date.now();
    const timer = window.setInterval(() => setElapsed(Date.now() - started), 500);
    return () => window.clearInterval(timer);
  }, [label]);

  const text = label ?? [...STAGES].reverse().find((s) => elapsed >= s.after)!.text;

  return (
    <div className="msg assistant" role="status" aria-live="polite">
      <div className="avatar thinking" aria-hidden="true">
        🤖
      </div>
      <div className="bubble loading-bubble">
        <span className="dots" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
        <span key={text} className="loading-text">
          {text}
        </span>
      </div>
    </div>
  );
}
