import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api/client';
import { ChatInput } from './components/ChatInput';
import { ChatWindow } from './components/ChatWindow';
import {
  IconBookmark,
  IconCompose,
  IconCopy,
  IconDownload,
  IconInfo,
  IconInspector,
  IconKeyboard,
  IconMonitor,
  IconMoon,
  IconMore,
  IconSettings,
  IconSidebar,
  IconSun,
  IconTrash,
} from './components/Icons';
import { ChatActionsContext } from './components/ChatActionsContext';
import { JobActionsContext, type JobActions } from './components/JobActionsContext';
import { JobDetails } from './components/JobDetails';
import { Menu } from './components/Menu';
import { ConfirmDialog, type ConfirmRequest } from './components/Modal';
import { RagInspector } from './components/RagInspector';
import { SavedJobsPanel } from './components/SavedJobsPanel';
import { SettingsDialog, type SettingsTab } from './components/SettingsDialog';
import { Sidebar } from './components/Sidebar';
import { useConversations } from './hooks/useConversations';
import { MAX_SELECTED, useChat } from './hooks/useChat';
import { useSavedJobs } from './hooks/useSavedJobs';
import { focusChatInput, IS_MAC, useShortcuts } from './hooks/useShortcuts';
import { useTheme } from './hooks/useTheme';
import type { SystemStatus } from './types';
import { loadSidebarOpen, saveSidebarOpen } from './utils/storage';

const isNarrow = () => typeof window !== 'undefined' && window.matchMedia('(max-width: 900px)').matches;
const MOD = IS_MAC ? '⌘' : 'Ctrl+';

function shortModel(model: string): string {
  const name = model.split('/').pop() ?? model;
  return name.replace(/:free$/, '').replace(/-fin$/, '');
}

function slug(s: string): string {
  return (
    s
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .slice(0, 60) || 'chat'
  );
}

export default function App() {
  const chat = useChat();
  const { theme, setTheme } = useTheme();
  const saved = useSavedJobs();
  const conversations = useConversations(chat.conversationsVersion);

  const [draft, setDraft] = useState('');
  const [focusToken, setFocusToken] = useState(0);
  const [detailJobId, setDetailJobId] = useState<number | null>(null);
  const [focusTitle, setFocusTitle] = useState<{ id: number; label: string } | null>(null);
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [statusError, setStatusError] = useState(false);
  const [savedOpen, setSavedOpen] = useState(false);
  const [settings, setSettings] = useState<{ open: boolean; tab: SettingsTab }>({ open: false, tab: 'general' });
  const [confirm, setConfirm] = useState<ConfirmRequest | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);

  // Desktop remembers the last choice; on phones the sidebar is an overlay that starts closed.
  const [sidebarOpen, setSidebarOpenState] = useState(() => !isNarrow() && loadSidebarOpen());
  const setSidebarOpen = useCallback((open: boolean) => {
    setSidebarOpenState(open);
    if (!isNarrow()) saveSidebarOpen(open);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api
        .status()
        .then((s) => {
          if (cancelled) return;
          setStatus(s);
          setStatusError(false);
        })
        .catch(() => !cancelled && setStatusError(true));
    void load();
    const timer = window.setInterval(load, 30000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  const { send, setFocusJobId, toggleSelected, selectedJobs, knownJob, focusJobId, newChat, openConversation } = chat;

  const startNewChat = useCallback(() => {
    newChat();
    setFocusTitle(null);
    setDraft('');
    setDetailJobId(null);
    setRenaming(false);
    if (isNarrow()) setSidebarOpen(false);
    requestAnimationFrame(focusChatInput);
  }, [newChat, setSidebarOpen]);

  const selectConversation = useCallback(
    (id: string) => {
      if (id !== chat.conversationId) {
        openConversation(id);
        setFocusTitle(null);
        setDraft('');
        setDetailJobId(null);
        setRenaming(false);
      }
      if (isNarrow()) setSidebarOpen(false);
    },
    [chat.conversationId, openConversation, setSidebarOpen],
  );

  const handleSend = useCallback(
    (text: string) => {
      void send(text);
      setDraft('');
    },
    [send],
  );

  const actions: JobActions = useMemo(
    () => ({
      openDetails: (id) => setDetailJobId(id),
      toggleCompare: toggleSelected,
      isSelected: (id) => selectedJobs.includes(id),
      canSelectMore: selectedJobs.length < MAX_SELECTED,
      askAbout: (job) => {
        setFocusJobId(job.id);
        setFocusTitle({ id: job.id, label: `${job.title} (Job #${job.id})` });
        setDraft('What skills does this job require?');
        setFocusToken((t) => t + 1);
      },
      sendSuggestion: handleSend,
    }),
    [handleSend, selectedJobs, setFocusJobId, toggleSelected],
  );

  // ---- current conversation title ------------------------------------------------------
  const current = conversations.items.find((c) => c.id === chat.conversationId);
  const firstUser = chat.messages.find((m) => m.kind === 'user' || (m.kind === 'history' && m.role === 'USER'));
  const firstText = firstUser && (firstUser.kind === 'user' || firstUser.kind === 'history') ? firstUser.text : null;
  const title = current?.title ?? (firstText ? (firstText.length > 60 ? `${firstText.slice(0, 57)}…` : firstText) : 'New chat');
  const hasChat = chat.messages.length > 0;

  useEffect(() => {
    document.title = hasChat ? `${title} · Job Search Assistant` : 'Job Search Assistant';
  }, [title, hasChat]);

  const deleteConversation = useCallback(
    (id: string, label: string) =>
      setConfirm({
        title: 'Delete chat?',
        message: (
          <>
            This will permanently delete <strong>{label}</strong>.
          </>
        ),
        confirmLabel: 'Delete',
        danger: true,
        onConfirm: async () => {
          const ok = await conversations.remove(id);
          if (ok && id === chat.conversationId) startNewChat();
        },
      }),
    [chat.conversationId, conversations, startNewChat],
  );

  const clearAll = () =>
    setConfirm({
      title: 'Delete all chats?',
      message: 'Every conversation and its search context will be permanently deleted. Saved jobs are kept.',
      confirmLabel: 'Delete all',
      danger: true,
      onConfirm: async () => {
        setSettings((s) => ({ ...s, open: false }));
        if (await conversations.clearAll()) startNewChat();
      },
    });

  const downloadChat = () => {
    const blob = new Blob([chat.exportMarkdown()], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${slug(title)}.md`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const commitTitle = () => {
    setRenaming(false);
    if (chat.conversationId) void conversations.rename(chat.conversationId, titleDraft);
  };

  const openSettings = (tab: SettingsTab = 'general') => setSettings({ open: true, tab });

  useShortcuts({
    newChat: startNewChat,
    searchChats: () => {
      setSidebarOpen(true);
      requestAnimationFrame(() => searchRef.current?.focus());
    },
    toggleSidebar: () => setSidebarOpen(!sidebarOpen),
    focusInput: focusChatInput,
    showShortcuts: () => openSettings('shortcuts'),
  });

  const focusLabel = (() => {
    if (focusJobId === null) return null;
    if (focusTitle?.id === focusJobId) return focusTitle.label;
    const known = knownJob(focusJobId);
    return known ? `${known.title} (Job #${focusJobId})` : `Job #${focusJobId}`;
  })();

  // The latest chat response is the freshest signal; fall back to the status endpoint.
  const lastAssistant = [...chat.messages].reverse().find((m) => m.kind === 'assistant');
  const lastAiAvailable = lastAssistant?.kind === 'assistant' ? lastAssistant.response.aiAvailable : null;
  const aiState: 'on' | 'down' | 'off' = !status?.aiConfigured
    ? 'off'
    : lastAiAvailable !== null
      ? lastAiAvailable
        ? 'on'
        : 'down'
      : status.aiStatus === 'UNAVAILABLE'
        ? 'down'
        : 'on';

  const statusPill = statusError ? (
    <span className="pill offline" title="Backend unreachable">
      <span className="dot" />
      <span className="pill-text">Offline</span>
    </span>
  ) : status ? (
    <span
      className={`pill ${aiState === 'on' ? 'online' : 'degraded'}`}
      title={`${status.aiLastError && aiState === 'down' ? 'Last AI error: ' + status.aiLastError + ' · ' : ''}Chat: ${status.chatModel} · Embeddings: ${status.index.embeddingProvider}/${status.index.embeddingModel} · ${status.index.chunkCount} chunks for ${status.index.jobCount} jobs`}
    >
      <span className="dot" />
      <span className="pill-text">{aiState === 'on' ? shortModel(status.chatModel) : 'Search-only mode'}</span>
    </span>
  ) : null;


  return (
    <JobActionsContext.Provider value={actions}>
      <ChatActionsContext.Provider value={chat.chatActions}>
      <div className={`shell${sidebarOpen ? ' sidebar-open' : ''}`}>
        <Sidebar
          open={sidebarOpen}
          items={conversations.items}
          loaded={conversations.loaded}
          error={conversations.error}
          activeId={chat.conversationId}
          disabled={chat.loading}
          savedCount={saved.savedCount}
          searchRef={searchRef}
          onSelect={selectConversation}
          onNewChat={startNewChat}
          onRename={(id, t) => void conversations.rename(id, t)}
          onDelete={(c) => deleteConversation(c.id, c.title)}
          onOpenSaved={() => {
            setSavedOpen(true);
            if (isNarrow()) setSidebarOpen(false);
          }}
          onClose={() => setSidebarOpen(false)}
          footer={
            <Menu
              label="Account and settings"
              placement="above"
              align="start"
              tooltip={false}
              triggerClassName="user-btn"
              trigger={
                <>
                  <span className="user-avatar" aria-hidden="true">
                    G
                  </span>
                  <span className="user-meta">
                    <span className="user-name">Guest</span>
                    <span className="user-plan">Demo workspace</span>
                  </span>
                  <IconMore size={16} />
                </>
              }
              items={[
                { label: 'Settings', icon: <IconSettings size={16} />, onSelect: () => openSettings('general') },
                {
                  label: 'Keyboard shortcuts',
                  icon: <IconKeyboard size={16} />,
                  hint: `${MOD}/`,
                  onSelect: () => openSettings('shortcuts'),
                },
                'divider',
                { label: 'Light', icon: <IconSun size={16} />, checked: theme === 'light', onSelect: () => setTheme('light') },
                { label: 'Dark', icon: <IconMoon size={16} />, checked: theme === 'dark', onSelect: () => setTheme('dark') },
                { label: 'System', icon: <IconMonitor size={16} />, checked: theme === 'system', onSelect: () => setTheme('system') },
                'divider',
                { label: 'About', icon: <IconInfo size={16} />, onSelect: () => openSettings('about') },
              ]}
            />
          }
        />

        <div className={`app${chat.debugMode ? ' with-inspector' : ''}`}>
          <header className="app-header">
            <div className="header-left">
              {!sidebarOpen && (
                <>
                  <button
                    type="button"
                    className="icon-btn"
                    onClick={() => setSidebarOpen(true)}
                    title={`Open sidebar (${MOD}Shift+S)`}
                    aria-label="Open sidebar"
                  >
                    <IconSidebar />
                  </button>
                  <button
                    type="button"
                    className="icon-btn hide-narrow"
                    onClick={startNewChat}
                    title={`New chat (${MOD}Shift+O)`}
                    aria-label="New chat"
                  >
                    <IconCompose />
                  </button>
                </>
              )}
              {renaming ? (
                <input
                  className="title-input"
                  value={titleDraft}
                  autoFocus
                  maxLength={120}
                  onChange={(e) => setTitleDraft(e.target.value)}
                  onBlur={commitTitle}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commitTitle();
                    if (e.key === 'Escape') setRenaming(false);
                  }}
                  aria-label="Chat title"
                />
              ) : (
                <h1 className="conv-title" title={title}>
                  {title}
                </h1>
              )}
              {hasChat && chat.conversationId && (
                <Menu
                  label="Chat options"
                  align="start"
                  trigger={<IconMore />}
                  items={[
                    {
                      label: 'Rename',
                      icon: <IconCompose size={16} />,
                      onSelect: () => {
                        setTitleDraft(title);
                        setRenaming(true);
                      },
                    },
                    { label: 'Copy chat', icon: <IconCopy size={16} />, onSelect: () => void chat.copyConversation() },
                    { label: 'Download (.md)', icon: <IconDownload size={16} />, onSelect: downloadChat },
                    {
                      label: chat.debugMode ? 'Hide RAG Inspector' : 'Show RAG Inspector',
                      icon: <IconInspector size={16} />,
                      onSelect: () => chat.setDebugMode(!chat.debugMode),
                    },
                    'divider',
                    {
                      label: 'Delete',
                      icon: <IconTrash size={16} />,
                      danger: true,
                      onSelect: () => deleteConversation(chat.conversationId!, title),
                    },
                  ]}
                />
              )}
            </div>
            <div className="header-right">
              {statusPill}
              <span className="pill demo hide-narrow" title="All jobs are fictional sample data">
                Demo data
              </span>
              <button
                type="button"
                className="icon-btn badge-host"
                onClick={() => setSavedOpen(true)}
                title="Saved jobs"
                aria-label={`Saved jobs (${saved.savedCount})`}
              >
                <IconBookmark />
                {saved.savedCount > 0 && <span className="count-dot">{saved.savedCount}</span>}
              </button>
            </div>
          </header>

          <main className="main">
            <section className="chat-column" aria-label="Chat">
              <ChatWindow
                messages={chat.messages}
                loading={chat.loading || chat.comparing}
                loadingHistory={chat.loadingHistory}
                onSend={handleSend}
                onRetry={chat.retry}
              />

              <div className="composer-dock">
                <div className="composer-inner">
                  {selectedJobs.length > 0 && (
                    <div className="compare-bar" role="region" aria-label="Compare selection">
                      <span>
                        <strong>{selectedJobs.length}</strong> selected
                        <span className="compare-ids"> · {selectedJobs.map((id) => `#${id}`).join(', ')}</span>
                        {selectedJobs.length < 2 && <span className="compare-hint"> — select 1 more job to compare</span>}
                      </span>
                      <div>
                        <button
                          type="button"
                          className="btn btn-primary btn-sm"
                          disabled={selectedJobs.length < 2 || chat.comparing}
                          onClick={() => void chat.compareSelected()}
                          title={selectedJobs.length < 2 ? 'Select at least 2 jobs' : 'Compare selected jobs'}
                        >
                          {chat.comparing ? 'Comparing…' : 'Compare'}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={chat.clearSelected}>
                          Clear
                        </button>
                      </div>
                    </div>
                  )}

                  <ChatInput
                    value={draft}
                    onChange={setDraft}
                    onSend={handleSend}
                    disabled={chat.loading}
                    focusLabel={focusLabel}
                    focusToken={focusToken}
                    onClearFocus={() => {
                      chat.setFocusJobId(null);
                      setFocusTitle(null);
                    }}
                  />
                  <p className="composer-note">Answers come from demo job listings. AI can make mistakes — check the details.</p>
                </div>
              </div>
            </section>

            {chat.debugMode && <RagInspector trace={chat.latestTrace} onClose={() => chat.setDebugMode(false)} />}
          </main>
        </div>

        {detailJobId !== null && <JobDetails jobId={detailJobId} onClose={() => setDetailJobId(null)} />}
        <SavedJobsPanel open={savedOpen} onClose={() => setSavedOpen(false)} />
        <SettingsDialog
          open={settings.open}
          initialTab={settings.tab}
          onClose={() => setSettings((s) => ({ ...s, open: false }))}
          theme={theme}
          onThemeChange={setTheme}
          showInspector={chat.debugMode}
          onShowInspectorChange={chat.setDebugMode}
          conversationCount={conversations.items.length}
          onClearAll={clearAll}
          status={status}
          statusError={statusError}
        />
        <ConfirmDialog request={confirm} onClose={() => setConfirm(null)} />
      </div>
      </ChatActionsContext.Provider>
    </JobActionsContext.Provider>
  );
}
