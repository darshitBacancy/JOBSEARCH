import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { UiMessage } from '../types';
import { BrandMark } from './BrandMark';
import { ChatMessage } from './ChatMessage';
import { IconArrowDown } from './Icons';
import { LoadingIndicator } from './LoadingIndicator';

interface Props {
  messages: UiMessage[];
  loading: boolean;
  loadingHistory?: boolean;
  onSend: (text: string) => void;
  onRetry: (errorId: string, retryMessage: string) => void;
}

const STARTERS = [
  { icon: '☕', title: 'Remote Java roles', prompt: 'Find remote Java Spring Boot jobs' },
  { icon: '⚛️', title: 'React in Bangalore', prompt: 'Find React jobs in Bangalore for 3-5 years experience' },
  { icon: '💰', title: 'High-paying roles', prompt: 'Show me jobs paying above ₹20 LPA' },
  { icon: '☁️', title: 'Cloud & DevOps', prompt: 'Show remote DevOps jobs that need AWS and Kubernetes' },
  { icon: '🤖', title: 'AI / ML engineering', prompt: 'Find machine learning or GenAI engineer jobs' },
  { icon: '🎯', title: 'Interview prep', prompt: 'How should I prepare for a Spring Boot backend interview?' },
];

function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return 'Working late?';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

function Welcome({ onSend, disabled }: { onSend: (t: string) => void; disabled: boolean }) {
  return (
    <div className="welcome">
      <div className="welcome-glow" aria-hidden="true" />
      <BrandMark size={56} className="welcome-mark" />
      <h2 className="welcome-title">
        <span className="gradient-text">{greeting()}</span>
        <br />
        What job are you looking for?
      </h2>
      <p className="welcome-tagline">
        Describe your skills, experience, location or salary in plain language. I search the job listings and answer only
        from what I find.
      </p>
      <div className="welcome-grid" role="group" aria-label="Suggestions">
        {STARTERS.map((s) => (
          <button key={s.title} type="button" className="welcome-card" onClick={() => onSend(s.prompt)} disabled={disabled}>
            <span className="welcome-card-icon" aria-hidden="true">
              {s.icon}
            </span>
            <span className="welcome-card-text">
              <strong>{s.title}</strong>
              <span>{s.prompt}</span>
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

export function ChatWindow({ messages, loading, loadingHistory = false, onSend, onRetry }: Props) {
  const scroller = useRef<HTMLDivElement>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const nearBottom = useRef(true);
  const [showJump, setShowJump] = useState(false);

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    nearBottom.current = distance < 160;
    setShowJump(distance > 400);
  };

  // Follow new messages unless the user scrolled up to read; always follow their own message.
  const last = messages[messages.length - 1];
  useLayoutEffect(() => {
    if (last?.kind === 'comparison') {
      // The user asked for this from a card higher up: bring the new table into view from its top.
      const tables = scroller.current?.querySelectorAll('.comparison');
      tables?.[tables.length - 1]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    // Follow new content when already at the bottom, after the user sends, or when an action they started begins.
    if (nearBottom.current || last?.kind === 'user' || loading) {
      endRef.current?.scrollIntoView({ behavior: messages.length > 1 ? 'smooth' : 'auto', block: 'end' });
    }
  }, [messages.length, loading, last?.kind]);

  useEffect(() => {
    nearBottom.current = true;
    setShowJump(false);
  }, [loadingHistory]);

  const empty = messages.length === 0;

  return (
    <div className="chat-window" ref={scroller} onScroll={onScroll} aria-live="polite">
      <div className={`chat-thread${empty ? ' empty' : ''}`}>
        {loadingHistory && empty ? (
          <div className="thread-skeleton" aria-label="Loading conversation">
            <span className="sk sk-user" />
            <span className="sk sk-line" />
            <span className="sk sk-line short" />
            <span className="sk sk-user" />
            <span className="sk sk-line" />
          </div>
        ) : empty ? (
          <Welcome onSend={onSend} disabled={loading} />
        ) : (
          messages.map((m) => <ChatMessage key={m.id} message={m} onRetry={onRetry} />)
        )}
        {loading && !loadingHistory && <LoadingIndicator />}
        <div ref={endRef} />
      </div>
      {showJump && (
        <button
          type="button"
          className="jump-btn"
          aria-label="Scroll to latest message"
          onClick={() => endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })}
        >
          <IconArrowDown size={16} />
        </button>
      )}
    </div>
  );
}
