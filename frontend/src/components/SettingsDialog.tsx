import { useEffect, useState, type ReactNode } from 'react';
import { SHORTCUTS } from '../hooks/useShortcuts';
import type { SystemStatus } from '../types';
import type { ThemePreference } from '../utils/storage';
import { BrandMark } from './BrandMark';
import { IconDatabase, IconInfo, IconKeyboard, IconMonitor, IconMoon, IconSettings, IconSun } from './Icons';
import { Modal } from './Modal';

export type SettingsTab = 'general' | 'data' | 'shortcuts' | 'about';

interface Props {
  open: boolean;
  initialTab?: SettingsTab;
  onClose: () => void;
  theme: ThemePreference;
  onThemeChange: (t: ThemePreference) => void;
  showInspector: boolean;
  onShowInspectorChange: (v: boolean) => void;
  conversationCount: number;
  onClearAll: () => void;
  status: SystemStatus | null;
  statusError: boolean;
}

const TABS: { id: SettingsTab; label: string; icon: ReactNode }[] = [
  { id: 'general', label: 'General', icon: <IconSettings size={16} /> },
  { id: 'data', label: 'Data controls', icon: <IconDatabase size={16} /> },
  { id: 'shortcuts', label: 'Shortcuts', icon: <IconKeyboard size={16} /> },
  { id: 'about', label: 'About', icon: <IconInfo size={16} /> },
];

const THEMES: { id: ThemePreference; label: string; icon: ReactNode }[] = [
  { id: 'light', label: 'Light', icon: <IconSun size={16} /> },
  { id: 'dark', label: 'Dark', icon: <IconMoon size={16} /> },
  { id: 'system', label: 'System', icon: <IconMonitor size={16} /> },
];

function Row({ title, desc, children }: { title: string; desc?: string; children: ReactNode }) {
  return (
    <div className="settings-row">
      <div>
        <div className="settings-row-title">{title}</div>
        {desc && <div className="settings-row-desc">{desc}</div>}
      </div>
      <div className="settings-row-control">{children}</div>
    </div>
  );
}

export function SettingsDialog({
  open,
  initialTab = 'general',
  onClose,
  theme,
  onThemeChange,
  showInspector,
  onShowInspectorChange,
  conversationCount,
  onClearAll,
  status,
  statusError,
}: Props) {
  const [tab, setTab] = useState<SettingsTab>(initialTab);
  useEffect(() => {
    if (open) setTab(initialTab);
  }, [open, initialTab]);

  return (
    <Modal open={open} onClose={onClose} title="Settings" className="modal-settings">
      <div className="settings">
        <nav className="settings-tabs" role="tablist" aria-label="Settings sections">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              id={`settings-tab-${t.id}`}
              aria-selected={tab === t.id}
              aria-controls={`settings-panel-${t.id}`}
              className={`settings-tab${tab === t.id ? ' active' : ''}`}
              onClick={() => setTab(t.id)}
            >
              {t.icon}
              <span>{t.label}</span>
            </button>
          ))}
        </nav>

        <section
          className="settings-panel"
          role="tabpanel"
          id={`settings-panel-${tab}`}
          aria-labelledby={`settings-tab-${tab}`}
        >
          {tab === 'general' && (
            <>
              <Row title="Theme" desc="Match your system or pick a fixed look.">
                <div className="segmented" role="radiogroup" aria-label="Theme">
                  {THEMES.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      role="radio"
                      aria-checked={theme === t.id}
                      className={theme === t.id ? 'active' : ''}
                      onClick={() => onThemeChange(t.id)}
                    >
                      {t.icon}
                      {t.label}
                    </button>
                  ))}
                </div>
              </Row>
              <Row
                title="RAG Inspector"
                desc="Show how each answer was retrieved: criteria, retrieved chunks, scores and tool calls."
              >
                <label className="switch">
                  <input
                    type="checkbox"
                    checked={showInspector}
                    onChange={(e) => onShowInspectorChange(e.target.checked)}
                    aria-label="Show RAG Inspector"
                  />
                </label>
              </Row>
            </>
          )}

          {tab === 'data' && (
            <>
              <Row
                title="Chat history"
                desc={`${conversationCount} saved conversation${conversationCount === 1 ? '' : 's'}, stored by this app's backend.`}
              >
                <button
                  type="button"
                  className="btn btn-danger-ghost btn-sm"
                  onClick={onClearAll}
                  disabled={conversationCount === 0}
                >
                  Delete all chats
                </button>
              </Row>
              <p className="settings-note">
                Deleting chats removes their messages and search context permanently. Saved jobs are kept.
              </p>
            </>
          )}

          {tab === 'shortcuts' && (
            <ul className="shortcut-list">
              {SHORTCUTS.map((s) => (
                <li key={s.label}>
                  <span>{s.label}</span>
                  <span className="keys">
                    {s.keys.map((k) => (
                      <kbd key={k}>{k}</kbd>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          )}

          {tab === 'about' && (
            <div className="about">
              <div className="about-brand">
                <BrandMark size={44} />
                <div>
                  <div className="about-name">Job Search Assistant</div>
                  <div className="settings-row-desc">Conversational job search grounded in a RAG pipeline.</div>
                </div>
              </div>
              <dl className="about-grid">
                <dt>Backend</dt>
                <dd>
                  {statusError ? (
                    <span className="status-text bad">Unreachable</span>
                  ) : status ? (
                    <span className="status-text ok">Connected</span>
                  ) : (
                    'Checking…'
                  )}
                </dd>
                <dt>AI model</dt>
                <dd>
                  {status ? (
                    <>
                      <code>{status.chatModel}</code>{' '}
                      <span className={`status-text ${status.aiStatus === 'UNAVAILABLE' ? 'bad' : 'ok'}`}>
                        {status.aiConfigured ? status.aiStatus.toLowerCase().replace('_', ' ') : 'not configured'}
                      </span>
                    </>
                  ) : (
                    '—'
                  )}
                </dd>
                <dt>Embeddings</dt>
                <dd>{status ? <code>{status.embeddingModel}</code> : '—'}</dd>
                <dt>Search index</dt>
                <dd>
                  {status
                    ? status.index.indexed
                      ? `${status.index.chunkCount} chunks · ${status.index.jobCount} jobs · ${status.index.dimensions}-dim`
                      : 'Building…'
                    : '—'}
                </dd>
                <dt>Vector store</dt>
                <dd>{status?.vectorStore ?? '—'}</dd>
              </dl>
              <p className="settings-note warn">
                All job listings, companies, salaries and links are fictional demo data, not real vacancies.
              </p>
            </div>
          )}
        </section>
      </div>
    </Modal>
  );
}
