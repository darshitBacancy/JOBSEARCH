import { Fragment, type ReactNode } from 'react';

/**
 * Minimal, safe markdown renderer: builds React nodes and never injects HTML.
 * Supports paragraphs, "- " bullets, "1. " numbered lists, headings, **bold**, and "Job #123" references.
 */
type JobRefHandler = (jobId: number) => void;

const INLINE = /(\*\*[^*]+\*\*|\[?Job #\d+\]?)/g;

function renderInline(text: string, onJobRef: JobRefHandler | undefined, keyPrefix: string): ReactNode[] {
  return text.split(INLINE).map((part, i) => {
    const key = `${keyPrefix}-${i}`;
    if (!part) return null;
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={key}>{renderInline(part.slice(2, -2), onJobRef, key)}</strong>;
    }
    const ref = /^\[?Job #(\d+)\]?$/.exec(part);
    if (ref) {
      const id = Number(ref[1]);
      return onJobRef ? (
        <button key={key} type="button" className="job-ref" onClick={() => onJobRef(id)} title="Open job details">
          Job #{id}
        </button>
      ) : (
        <span key={key} className="job-ref">
          Job #{id}
        </span>
      );
    }
    return <Fragment key={key}>{part}</Fragment>;
  });
}

type Block =
  | { type: 'p'; lines: string[] }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'h'; text: string };

function parse(text: string): Block[] {
  const blocks: Block[] = [];
  let current: Block | null = null;

  for (const raw of text.replace(/\r\n/g, '\n').split('\n')) {
    const trimmed = raw.trim();
    if (!trimmed) {
      if (current) blocks.push(current);
      current = null;
      continue;
    }
    const heading = /^#{1,4}\s+(.*)$/.exec(trimmed);
    const bullet = /^[-*•]\s+(.*)$/.exec(trimmed);
    const numbered = /^\d+[.)]\s+(.*)$/.exec(trimmed);

    if (heading) {
      if (current) blocks.push(current);
      current = null;
      blocks.push({ type: 'h', text: heading[1] });
    } else if (bullet || numbered) {
      const type = bullet ? 'ul' : 'ol';
      const item = (bullet ?? numbered)![1];
      if (current && current.type === type) {
        current.items.push(item);
      } else if (current && (current.type === 'ul' || current.type === 'ol') && /^\s{2,}/.test(raw)) {
        // nested list: keep as a continuation line of the parent item
        current.items[current.items.length - 1] += `\n• ${item}`;
      } else {
        if (current) blocks.push(current);
        current = { type, items: [item] };
      }
    } else if (current && (current.type === 'ul' || current.type === 'ol') && /^\s{2,}/.test(raw)) {
      current.items[current.items.length - 1] += `\n${trimmed}`;
    } else if (current && current.type === 'p') {
      current.lines.push(trimmed);
    } else {
      if (current) blocks.push(current);
      current = { type: 'p', lines: [trimmed] };
    }
  }
  if (current) blocks.push(current);
  return blocks;
}

function renderLines(text: string, onJobRef: JobRefHandler | undefined, key: string): ReactNode[] {
  return text.split('\n').flatMap((line, i) => {
    const nodes = renderInline(line, onJobRef, `${key}-${i}`);
    return i === 0 ? nodes : [<br key={`${key}-br-${i}`} />, ...nodes];
  });
}

export function Markdown({ text, onJobRef }: { text: string; onJobRef?: JobRefHandler }) {
  return (
    <div className="md">
      {parse(text).map((b, i) => {
        const key = `b${i}`;
        switch (b.type) {
          case 'h':
            return (
              <p key={key} className="md-h">
                {renderInline(b.text, onJobRef, key)}
              </p>
            );
          case 'ul':
            return (
              <ul key={key}>
                {b.items.map((it, j) => (
                  <li key={j}>{renderLines(it, onJobRef, `${key}-${j}`)}</li>
                ))}
              </ul>
            );
          case 'ol':
            return (
              <ol key={key}>
                {b.items.map((it, j) => (
                  <li key={j}>{renderLines(it, onJobRef, `${key}-${j}`)}</li>
                ))}
              </ol>
            );
          default:
            return <p key={key}>{renderLines(b.lines.join('\n'), onJobRef, key)}</p>;
        }
      })}
    </div>
  );
}
