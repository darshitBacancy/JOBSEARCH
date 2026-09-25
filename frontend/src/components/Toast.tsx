import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import '../chat-features.css';

export type ToastKind = 'info' | 'success' | 'error';

interface ToastApi {
  show: (message: string, kind?: ToastKind) => void;
}

interface ToastItem {
  id: number;
  message: string;
  kind: ToastKind;
}

const ToastContext = createContext<ToastApi | null>(null);

// Lets non-React code (e.g. the saved-jobs store) raise toasts through the mounted provider.
let globalShow: ToastApi['show'] | null = null;
export function showToast(message: string, kind: ToastKind = 'info') {
  globalShow?.(message, kind);
}

let seq = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const timers = useRef(new Map<number, number>());

  const dismiss = useCallback((id: number) => {
    setItems((list) => list.filter((t) => t.id !== id));
    const timer = timers.current.get(id);
    if (timer) window.clearTimeout(timer);
    timers.current.delete(id);
  }, []);

  const show = useCallback(
    (message: string, kind: ToastKind = 'info') => {
      const id = ++seq;
      setItems((list) => [...list.slice(-3), { id, message, kind }]);
      timers.current.set(id, window.setTimeout(() => dismiss(id), kind === 'error' ? 6000 : 2800));
    },
    [dismiss],
  );

  useEffect(() => {
    globalShow = show;
    const pending = timers.current;
    return () => {
      if (globalShow === show) globalShow = null;
      pending.forEach((t) => window.clearTimeout(t));
    };
  }, [show]);

  const api = useMemo(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-region" role="status" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`}>
            <span className="toast-icon" aria-hidden="true">
              {t.kind === 'success' ? '✓' : t.kind === 'error' ? '!' : 'i'}
            </span>
            <span className="toast-text">{t.message}</span>
            <button type="button" className="toast-close" onClick={() => dismiss(t.id)} aria-label="Dismiss">
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/** Falls back to a no-op outside the provider so components never crash in isolation. */
export function useToast(): ToastApi {
  return useContext(ToastContext) ?? { show: (message, kind) => showToast(message, kind) };
}
