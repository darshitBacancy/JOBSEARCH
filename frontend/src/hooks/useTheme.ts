import { useCallback, useEffect, useState } from 'react';
import { loadTheme, saveTheme, type ThemePreference } from '../utils/storage';

const query = () => window.matchMedia('(prefers-color-scheme: dark)');

/**
 * Light / Dark / System theme. The resolved theme is written to <html data-theme>, which the
 * CSS tokens key off. index.html applies the stored choice before first paint to avoid a flash.
 */
export function useTheme() {
  const [theme, setThemeState] = useState<ThemePreference>(loadTheme);
  const [systemDark, setSystemDark] = useState(() => query().matches);

  useEffect(() => {
    const mq = query();
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const resolved: 'light' | 'dark' = theme === 'system' ? (systemDark ? 'dark' : 'light') : theme;

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.theme = resolved;
    root.style.colorScheme = resolved;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolved === 'dark' ? '#111318' : '#ffffff');
  }, [resolved]);

  const setTheme = useCallback((next: ThemePreference) => {
    setThemeState(next);
    saveTheme(next);
  }, []);

  return { theme, resolved, setTheme };
}
