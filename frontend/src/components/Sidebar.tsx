import { useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from 'react';
import type { ConversationSummary } from '../types';
import { BrandMark } from './BrandMark';
import { IconBookmark, IconCompose, IconSearch, IconSidebar, IconTrash } from './Icons';

interface Props {
  open: boolean;
  items: ConversationSummary[];
  loaded: boolean;
  error: string | null;
  activeId: string | null;
  disabled: boolean;
  savedCount: number;
  searchRef: RefObject<HTMLInputElement | null>;
  footer: ReactNode;
  onSelect: (id: string) => void;
  onNewChat: () => void;
  onRename: (id: string, title: string) => void;
  onDelete: (c: ConversationSummary) => void;
  onOpenSaved: () => void;
  onClose: () => void;
}

const GROUPS = ['Today', 'Yesterday', 'Previous 7 days', 'Previous 30 days', 'Older'] as const;

function groupOf(iso: string, now: Date): (typeof GROUPS)[number] {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return 'Older';
  const day = 86_400_000;
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (t >= today) return 'Today';
  if (t >= today - day) return 'Yesterday';
  if (t >= today - 7 * day) return 'Previous 7 days';
  if (t >= today - 30 * day) return 'Previous 30 days';
  return 'Older';
}

export function Sidebar(props: Props) {
  const { open, items, loaded, error, activeId, disabled, savedCount, searchRef, footer } = props;
  const [query, setQuery] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState('');
  const editRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editingId) editRef.current?.select();
  }, [editingId]);

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const now = new Date();
    const map = new Map<string, ConversationSummary[]>();
    for (const c of items) {
      if (q && !c.title.toLowerCase().includes(q)) continue;
      const g = groupOf(c.updatedAt, now);
      map.set(g, [...(map.get(g) ?? []), c]);
    }
    return GROUPS.filter((g) => map.has(g)).map((g) => ({ label: g, items: map.get(g)! }));
  }, [items, query]);

  const commit = (id: string) => {
    setEditingId(null);
    props.onRename(id, editValue);
  };

  return (
    <>
      <div className={`sidebar-backdrop${open ? ' show' : ''}`} onClick={props.onClose} aria-hidden="true" />
      <aside className={`sidebar${open ? ' open' : ''}`} aria-label="Chat history" aria-hidden={!open}>
        <div className="sidebar-top">
          <div className="sidebar-brand">
            <BrandMark size={30} />
            <span>Job Search Assistant</span>
          </div>
          <button type="button" className="icon-btn" onClick={props.onClose} title="Close sidebar" aria-label="Close sidebar">
            <IconSidebar />
          </button>
        </div>

        <div className="sidebar-nav">
          <button type="button" className="sidebar-row primary" onClick={props.onNewChat} disabled={disabled}>
            <IconCompose size={17} />
            <span>New chat</span>
          </button>
          <button type="button" className="sidebar-row" onClick={props.onOpenSaved}>
            <IconBookmark size={17} />
            <span>Saved jobs</span>
            {savedCount > 0 && <span className="count-badge">{savedCount}</span>}
          </button>
          <label className="sidebar-search">
            <IconSearch size={16} />
            <input
              ref={searchRef}
              type="search"
              placeholder="Search chats"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Escape' && setQuery('')}
              aria-label="Search chats"
            />
          </label>
        </div>

        <nav className="sidebar-list" aria-label="Conversations">
          {error && <p className="sidebar-note error">Couldn't load chats. {error}</p>}
          {!loaded && !error && (
            <div className="sidebar-skeleton" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
          )}
          {loaded && !error && items.length === 0 && (
            <p className="sidebar-note">Your conversations will appear here.</p>
          )}
          {loaded && items.length > 0 && groups.length === 0 && <p className="sidebar-note">No chats match "{query}".</p>}

          {groups.map((g) => (
            <section key={g.label} className="sidebar-group">
              <h3>{g.label}</h3>
              <ul>
                {g.items.map((c) => (
                  <li key={c.id} className={`sidebar-item${c.id === activeId ? ' active' : ''}`}>
                    {editingId === c.id ? (
                      <input
                        ref={editRef}
                        className="sidebar-rename"
                        value={editValue}
                        maxLength={120}
                        onChange={(e) => setEditValue(e.target.value)}
                        onBlur={() => commit(c.id)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') commit(c.id);
                          if (e.key === 'Escape') setEditingId(null);
                        }}
                        aria-label="Chat title"
                      />
                    ) : (
                      <>
                        <button
                          type="button"
                          className="sidebar-link"
                          onClick={() => props.onSelect(c.id)}
                          onDoubleClick={() => {
                            setEditingId(c.id);
                            setEditValue(c.title);
                          }}
                          disabled={disabled && c.id !== activeId}
                          title={c.title}
                          aria-current={c.id === activeId ? 'page' : undefined}
                        >
                          {c.title}
                        </button>
                        <span className="sidebar-actions">
                          <button
                            type="button"
                            className="icon-btn sm"
                            title="Rename"
                            aria-label={`Rename ${c.title}`}
                            onClick={() => {
                              setEditingId(c.id);
                              setEditValue(c.title);
                            }}
                          >
                            <IconCompose size={14} />
                          </button>
                          <button
                            type="button"
                            className="icon-btn sm danger"
                            title="Delete"
                            aria-label={`Delete ${c.title}`}
                            onClick={() => props.onDelete(c)}
                          >
                            <IconTrash size={14} />
                          </button>
                        </span>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </nav>

        <div className="sidebar-foot">{footer}</div>
      </aside>
    </>
  );
}
