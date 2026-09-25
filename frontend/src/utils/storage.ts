const KEY = 'jsa.conversationId';

export function loadConversationId(): string | null {
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function saveConversationId(id: string | null): void {
  try {
    if (id) window.localStorage.setItem(KEY, id);
    else window.localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable (private mode etc.) */
  }
}

const SIDEBAR_KEY = 'jsa.sidebarOpen';

export function loadSidebarOpen(): boolean {
  try {
    return window.localStorage.getItem(SIDEBAR_KEY) !== 'false';
  } catch {
    return true;
  }
}

export function saveSidebarOpen(open: boolean): void {
  try {
    window.localStorage.setItem(SIDEBAR_KEY, String(open));
  } catch {
    /* storage unavailable */
  }
}

export type ThemePreference = 'light' | 'dark' | 'system';
const THEME_KEY = 'jsa.theme';

export function loadTheme(): ThemePreference {
  try {
    const v = window.localStorage.getItem(THEME_KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

export function saveTheme(theme: ThemePreference): void {
  try {
    if (theme === 'system') window.localStorage.removeItem(THEME_KEY);
    else window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    /* storage unavailable */
  }
}
