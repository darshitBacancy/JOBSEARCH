import { useEffect, useRef } from 'react';

export const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
const MOD = IS_MAC ? '⌘' : 'Ctrl';

export const SHORTCUTS: { keys: string[]; label: string }[] = [
  { keys: [MOD, 'Shift', 'O'], label: 'New chat' },
  { keys: [MOD, 'K'], label: 'Search chats' },
  { keys: [MOD, 'Shift', 'S'], label: 'Toggle sidebar' },
  { keys: ['/'], label: 'Focus the message box' },
  { keys: [MOD, '/'], label: 'Show keyboard shortcuts' },
  { keys: ['Enter'], label: 'Send message' },
  { keys: ['Shift', 'Enter'], label: 'New line' },
  { keys: ['Esc'], label: 'Close dialogs and panels' },
];

export interface ShortcutHandlers {
  newChat: () => void;
  searchChats: () => void;
  toggleSidebar: () => void;
  focusInput: () => void;
  showShortcuts: () => void;
}

function isTyping(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  return el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName);
}

/** Global keyboard shortcuts (see SHORTCUTS). Handlers may change every render. */
export function useShortcuts(handlers: ShortcutHandlers) {
  const ref = useRef(handlers);
  ref.current = handlers;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mod = IS_MAC ? e.metaKey : e.ctrlKey;
      const key = e.key.toLowerCase();
      if (mod && e.shiftKey && key === 'o') {
        e.preventDefault();
        ref.current.newChat();
      } else if (mod && !e.shiftKey && key === 'k') {
        e.preventDefault();
        ref.current.searchChats();
      } else if (mod && e.shiftKey && key === 's') {
        e.preventDefault();
        ref.current.toggleSidebar();
      } else if (mod && !e.shiftKey && key === '/') {
        e.preventDefault();
        ref.current.showShortcuts();
      } else if (key === '/' && !mod && !e.altKey && !isTyping(e.target) && !document.querySelector('dialog[open]')) {
        e.preventDefault();
        ref.current.focusInput();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

export function focusChatInput() {
  document.querySelector<HTMLTextAreaElement>('.chat-input textarea')?.focus();
}
