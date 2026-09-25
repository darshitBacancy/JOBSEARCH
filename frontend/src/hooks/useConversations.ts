import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage } from '../api/client';
import { showToast } from '../components/Toast';
import type { ConversationSummary } from '../types';

/** DELETE /api/conversations - removes every conversation. */
async function deleteAllConversations(): Promise<void> {
  const res = await fetch('/api/conversations', { method: 'DELETE' });
  if (!res.ok && res.status !== 404) throw new Error(`Request failed (${res.status})`);
}

/**
 * The sidebar's conversation list. Refetches whenever {@code version} changes (the chat hook bumps
 * it after every reply) and applies rename / delete optimistically.
 */
export function useConversations(version: number) {
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .conversations()
      .then((list) => {
        if (cancelled) return;
        setItems(list);
        setError(null);
      })
      .catch((err) => !cancelled && setError(errorMessage(err)))
      .finally(() => !cancelled && setLoaded(true));
    return () => {
      cancelled = true;
    };
  }, [version, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  const rename = useCallback(
    async (id: string, title: string) => {
      const clean = title.trim();
      const before = items;
      if (!clean || before.find((c) => c.id === id)?.title === clean) return;
      setItems((list) => list.map((c) => (c.id === id ? { ...c, title: clean } : c)));
      try {
        await api.renameConversation(id, clean);
      } catch (err) {
        setItems(before);
        showToast(`Couldn't rename chat: ${errorMessage(err)}`, 'error');
      }
    },
    [items],
  );

  const remove = useCallback(async (id: string): Promise<boolean> => {
    try {
      await api.deleteConversation(id);
      setItems((list) => list.filter((c) => c.id !== id));
      showToast('Chat deleted', 'success');
      return true;
    } catch (err) {
      showToast(`Couldn't delete chat: ${errorMessage(err)}`, 'error');
      return false;
    }
  }, []);

  const clearAll = useCallback(async (): Promise<boolean> => {
    try {
      await deleteAllConversations();
      setItems([]);
      showToast('All chats deleted', 'success');
      return true;
    } catch (err) {
      showToast(`Couldn't clear chats: ${errorMessage(err)}`, 'error');
      refresh();
      return false;
    }
  }, [refresh]);

  return { items, loaded, error, refresh, rename, remove, clearAll };
}
