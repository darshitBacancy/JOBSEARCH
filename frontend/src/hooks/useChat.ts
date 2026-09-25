import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, errorMessage, isAbort } from '../api/client';
import type { ChatActions } from '../components/ChatActionsContext';
import { useToast } from '../components/Toast';
import type { ChatResponse, Feedback, JobCard, SearchFilters, UiMessage } from '../types';
import { cleanFilters, formatDate, formatTime } from '../utils/format';
import { loadConversationId, saveConversationId } from '../utils/storage';

export const MAX_SELECTED = 4;

let seq = 0;
const nextId = (prefix: string) => `${prefix}-${Date.now()}-${++seq}`;

const isUserMessage = (m: UiMessage) => m.kind === 'user' || (m.kind === 'history' && m.role === 'USER');
const isAssistantMessage = (m: UiMessage) => m.kind === 'assistant' || (m.kind === 'history' && m.role === 'ASSISTANT');

/** Stored message id behind a UI message, when the server has one. */
function serverIdOf(m: UiMessage): number | null {
  if (m.kind === 'history') return m.serverId;
  if (m.kind === 'user') return m.serverId ?? null;
  if (m.kind === 'assistant') return m.response.assistantMessageId ?? m.response.messageId ?? null;
  return null;
}

async function writeClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // Fallback for non-secure contexts
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }
}

function jobLine(j: JobCard): string {
  return `- **${j.title}** at ${j.company} (Job #${j.id}) · ${j.location}${j.remote ? ' · Remote' : ''} · ${j.salary} · ${j.experience}`;
}

export function useChat() {
  const toast = useToast();
  const [messages, setMessages] = useState<UiMessage[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(() => loadConversationId());
  const [loading, setLoading] = useState(false);
  const [jobs, setJobs] = useState<JobCard[]>([]);
  const [selectedJobs, setSelectedJobs] = useState<number[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<SearchFilters>({});
  const [debugMode, setDebugMode] = useState(false);
  const [focusJobId, setFocusJobId] = useState<number | null>(null);
  const [comparing, setComparing] = useState(false);

  // Cache of every job card seen, so selections survive new searches (used by the compare bar).
  const knownJobs = useRef(new Map<number, JobCard>());
  const rememberJobs = (cards: JobCard[]) => cards.forEach((j) => knownJobs.current.set(j.id, j));

  // Bumped whenever the server-side conversation list may have changed (sidebar refreshes on it).
  const [conversationsVersion, setConversationsVersion] = useState(0);
  const [loadingHistory, setLoadingHistory] = useState(false);
  // Guards against a slow history response overwriting a conversation opened after it.
  const activeLoad = useRef<string | null>(null);
  // In-flight chat request, so it can be stopped.
  const abortRef = useRef<AbortController | null>(null);

  const loadHistory = useCallback((id: string) => {
    activeLoad.current = id;
    setLoadingHistory(true);
    api
      .history(id)
      .then(async (stored) => {
        if (activeLoad.current !== id) return;
        setMessages(
          stored.map((m) => ({
            kind: 'history' as const,
            id: `h-${m.id}`,
            role: m.role,
            text: m.message,
            timestamp: m.timestamp,
            serverId: m.id,
            jobIds: m.jobIds ?? [],
            feedback: m.feedback ?? null,
          })),
        );
        // Bring back the job cards the answers showed, not just their text.
        const ids = [...new Set(stored.filter((m) => m.role === 'ASSISTANT').flatMap((m) => m.jobIds ?? []))];
        if (!ids.length) return;
        try {
          const cards = await api.jobCards(ids);
          if (activeLoad.current !== id || !cards) return;
          rememberJobs(cards);
          const byId = new Map(cards.map((c) => [c.id, c]));
          setMessages((current) =>
            current.map((m) =>
              m.kind === 'history' && m.role === 'ASSISTANT' && m.jobIds.length
                ? { ...m, jobs: m.jobIds.map((j) => byId.get(j)).filter((c): c is JobCard => !!c) }
                : m,
            ),
          );
        } catch {
          // Older backend without /jobs/cards: keep the text-only history.
        }
      })
      .catch(() => {
        if (activeLoad.current !== id) return;
        // Unknown/expired conversation: start fresh.
        setConversationId(null);
        saveConversationId(null);
      })
      .finally(() => {
        if (activeLoad.current === id) setLoadingHistory(false);
      });
  }, []);

  // Restore the previous conversation after a page reload.
  const restored = useRef(false);
  useEffect(() => {
    if (restored.current || !conversationId) return;
    restored.current = true;
    loadHistory(conversationId);
  }, [conversationId, loadHistory]);

  /** Applies a successful answer: ids, job cache, the answer bubble. */
  const acceptResponse = useCallback((res: ChatResponse, userUiId: string | null) => {
    setConversationId(res.conversationId);
    saveConversationId(res.conversationId);
    rememberJobs(res.jobs);
    if (res.comparison) rememberJobs(res.comparison.jobs);
    if (res.jobs.length) setJobs(res.jobs);
    if (res.focusJobId !== undefined) setFocusJobId(res.focusJobId);
    setMessages((m) => [
      ...m.map((x) =>
        userUiId && x.id === userUiId && x.kind === 'user' && res.userMessageId !== undefined
          ? { ...x, serverId: res.userMessageId }
          : x,
      ),
      { kind: 'assistant', id: nextId('a'), response: res, feedback: null },
    ]);
    setConversationsVersion((v) => v + 1);
  }, []);

  const send = useCallback(
    async (text: string) => {
      const message = text.trim();
      if (!message || loading) return;
      setError(null);
      const userUiId = nextId('u');
      setMessages((m) => [...m, { kind: 'user', id: userUiId, text: message, timestamp: new Date().toISOString() }]);
      setLoading(true);
      const controller = new AbortController();
      abortRef.current = controller;
      try {
        const res = await api.chat(
          {
            conversationId,
            message,
            selectedJobIds: selectedJobs.length ? selectedJobs : null,
            focusJobId,
            filters: cleanFilters(filters),
            debug: debugMode,
          },
          controller.signal,
        );
        acceptResponse(res, userUiId);
      } catch (err) {
        if (isAbort(err)) {
          setMessages((m) => [...m, { kind: 'stopped', id: nextId('s') }]);
          // The server may still have stored the exchange.
          setConversationsVersion((v) => v + 1);
        } else {
          const msg = errorMessage(err);
          setError(msg);
          setMessages((m) => [...m, { kind: 'error', id: nextId('e'), text: msg, retryMessage: message }]);
        }
      } finally {
        if (abortRef.current === controller) abortRef.current = null;
        setLoading(false);
      }
    },
    [acceptResponse, conversationId, debugMode, filters, focusJobId, loading, selectedJobs],
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const retry = useCallback(
    (errorId: string, retryMessage: string) => {
      // Drop the failed error bubble and the user message that caused it, then resend.
      setMessages((m) => {
        const idx = m.findIndex((x) => x.id === errorId);
        if (idx < 0) return m;
        const prev = m[idx - 1];
        const start = prev && prev.kind === 'user' && prev.text === retryMessage ? idx - 1 : idx;
        return [...m.slice(0, start), ...m.slice(idx + 1)];
      });
      void send(retryMessage);
    },
    [send],
  );

  /** The newest answer, if nothing but "stopped"/error notes follow it. */
  const lastAssistantId = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (isAssistantMessage(m)) return m.id;
      if (isUserMessage(m)) return null;
    }
    return null;
  }, [messages]);

  /** Re-answers the latest question: the server replaces its last user+assistant pair. */
  const regenerate = useCallback(async () => {
    if (loading || !conversationId || !lastAssistantId) return;
    const idx = messages.findIndex((m) => m.id === lastAssistantId);
    if (idx < 0) return;
    let userIdx = idx - 1;
    while (userIdx >= 0 && !isUserMessage(messages[userIdx])) userIdx--;
    const userUiId = userIdx >= 0 ? messages[userIdx].id : null;
    const previous = messages;
    setMessages(messages.slice(0, idx));
    setError(null);
    setLoading(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const res = await api.regenerate(conversationId, debugMode, controller.signal);
      // The server stored a fresh copy of the question: point the UI bubble at it.
      if (res.userMessageId !== undefined) {
        const newId = res.userMessageId;
        setMessages((m) =>
          m.map((x) => (x.id === userUiId && (x.kind === 'user' || x.kind === 'history') ? { ...x, serverId: newId } : x)),
        );
      }
      acceptResponse(res, null);
    } catch (err) {
      if (isAbort(err)) {
        setMessages((m) => [...m, { kind: 'stopped', id: nextId('s') }]);
      } else {
        setMessages(previous);
        toast.show(`Couldn't regenerate: ${errorMessage(err)}`, 'error');
      }
    } finally {
      if (abortRef.current === controller) abortRef.current = null;
      setLoading(false);
    }
  }, [acceptResponse, conversationId, debugMode, lastAssistantId, loading, messages, toast]);

  /** Replaces a user message (and everything after it) with an edited version, then re-asks. */
  const editAndResend = useCallback(
    async (uiId: string, text: string) => {
      const trimmed = text.trim();
      if (!trimmed || loading) return;
      const idx = messages.findIndex((m) => m.id === uiId);
      if (idx < 0) return;
      const serverId = serverIdOf(messages[idx]);
      if (conversationId && serverId !== null) {
        try {
          await api.truncate(conversationId, serverId);
        } catch (err) {
          toast.show(`Couldn't edit message: ${errorMessage(err)}`, 'error');
          return;
        }
      }
      setMessages(messages.slice(0, idx));
      setSelectedJobs([]);
      // send() appends through a state updater, so it builds on the truncated list.
      await send(trimmed);
    },
    [conversationId, loading, messages, send, toast],
  );

  const setFeedback = useCallback(
    async (uiId: string, rating: Feedback) => {
      const msg = messages.find((m) => m.id === uiId);
      if (!msg || (msg.kind !== 'assistant' && msg.kind !== 'history')) return;
      const serverId = serverIdOf(msg);
      const before = msg.feedback ?? null;
      const apply = (value: Feedback) =>
        setMessages((list) =>
          list.map((m) => (m.id === uiId && (m.kind === 'assistant' || m.kind === 'history') ? { ...m, feedback: value } : m)),
        );
      apply(rating);
      if (serverId === null) return;
      try {
        await api.feedback(serverId, rating);
        if (rating) toast.show('Thanks for the feedback', 'success');
      } catch (err) {
        apply(before);
        toast.show(`Couldn't save feedback: ${errorMessage(err)}`, 'error');
      }
    },
    [messages, toast],
  );

  const copyText = useCallback(
    async (text: string) => {
      const ok = await writeClipboard(text);
      toast.show(ok ? 'Copied to clipboard' : "Couldn't copy — clipboard access was blocked", ok ? 'success' : 'error');
    },
    [toast],
  );

  const toggleSelected = useCallback((job: JobCard) => {
    knownJobs.current.set(job.id, job);
    setSelectedJobs((sel) => {
      if (sel.includes(job.id)) return sel.filter((id) => id !== job.id);
      if (sel.length >= MAX_SELECTED) return sel;
      return [...sel, job.id];
    });
  }, []);

  const compareSelected = useCallback(async () => {
    if (selectedJobs.length < 2) return;
    setComparing(true);
    try {
      const comparison = await api.compare(selectedJobs);
      rememberJobs(comparison.jobs);
      setMessages((m) => [...m, { kind: 'comparison', id: nextId('c'), comparison }]);
    } catch (err) {
      const msg = errorMessage(err);
      setError(msg);
      setMessages((m) => [...m, { kind: 'error', id: nextId('e'), text: `Comparison failed: ${msg}`, retryMessage: null }]);
    } finally {
      setComparing(false);
    }
  }, [selectedJobs]);

  const resetState = () => {
    setJobs([]);
    setSelectedJobs([]);
    setFocusJobId(null);
    setError(null);
  };

  const openConversation = useCallback(
    (id: string) => {
      if (loading) return;
      resetState();
      setMessages([]);
      setConversationId(id);
      saveConversationId(id);
      restored.current = true;
      loadHistory(id);
    },
    [loadHistory, loading],
  );

  const newChat = useCallback(() => {
    abortRef.current?.abort();
    activeLoad.current = null;
    setLoadingHistory(false);
    setMessages([]);
    setConversationId(null);
    saveConversationId(null);
    resetState();
  }, []);

  /** The whole visible conversation as Markdown (for download / copy). */
  const exportMarkdown = useCallback((): string => {
    const lines: string[] = ['# Job Search Assistant — conversation', ''];
    const first = messages.find((m) => m.kind === 'user' || m.kind === 'history');
    const started = first && (first.kind === 'user' || first.kind === 'history') ? first.timestamp : new Date().toISOString();
    lines.push(`_Exported ${formatDate(new Date().toISOString())} · started ${formatDate(started)}_`, '');
    for (const m of messages) {
      if (m.kind === 'user' || (m.kind === 'history' && m.role === 'USER')) {
        lines.push(`### 🧑 You · ${formatTime(m.timestamp)}`, '', m.text, '');
      } else if (m.kind === 'history') {
        lines.push(`### 🤖 Assistant · ${formatTime(m.timestamp)}`, '', m.text, '');
        if (m.jobs?.length) lines.push(...m.jobs.map(jobLine), '');
      } else if (m.kind === 'assistant') {
        lines.push(`### 🤖 Assistant · ${formatTime(m.response.timestamp)}`, '', m.response.message, '');
        const shown = m.response.comparison?.jobs ?? m.response.jobs;
        if (shown.length) lines.push(...shown.map(jobLine), '');
      } else if (m.kind === 'comparison') {
        lines.push('### 🤖 Comparison', '', m.comparison.summary, '');
        const header = ['', ...m.comparison.jobs.map((j) => `#${j.id} ${j.title}`)];
        lines.push(`| ${header.join(' | ')} |`, `|${header.map(() => ' --- ').join('|')}|`);
        for (const row of m.comparison.rows) lines.push(`| ${[row.label, ...row.values].join(' | ')} |`);
        lines.push('');
      } else if (m.kind === 'error') {
        lines.push(`> ⚠ ${m.text}`, '');
      }
    }
    lines.push('---', '_All job listings are fictional demo data._');
    return lines.join('\n');
  }, [messages]);

  const copyConversation = useCallback(() => copyText(exportMarkdown()), [copyText, exportMarkdown]);

  const latestTrace = (() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.kind === 'assistant' && m.response.debug) return m.response.debug;
    }
    return null;
  })();

  const chatActions: ChatActions = useMemo(
    () => ({
      loading,
      lastAssistantId: conversationId ? lastAssistantId : null,
      regenerate: () => void regenerate(),
      editAndResend: (uiId, text) => void editAndResend(uiId, text),
      setFeedback: (uiId, rating) => void setFeedback(uiId, rating),
      copyText: (text) => void copyText(text),
    }),
    [conversationId, copyText, editAndResend, lastAssistantId, loading, regenerate, setFeedback],
  );

  return {
    messages,
    conversationId,
    conversationsVersion,
    loading,
    loadingHistory,
    jobs,
    selectedJobs,
    error,
    filters,
    debugMode,
    focusJobId,
    comparing,
    latestTrace,
    chatActions,
    knownJob: (id: number) => knownJobs.current.get(id),
    send,
    stop,
    retry,
    regenerate,
    editAndResend,
    setFeedback,
    exportMarkdown,
    copyConversation,
    setFilters,
    setDebugMode,
    setFocusJobId,
    toggleSelected,
    clearSelected: () => setSelectedJobs([]),
    compareSelected,
    newChat,
    openConversation,
  };
}
