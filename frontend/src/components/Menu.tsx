import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';

export type MenuEntry =
  | {
      label: string;
      icon?: ReactNode;
      onSelect: () => void;
      danger?: boolean;
      disabled?: boolean;
      hint?: string;
      checked?: boolean;
    }
  | 'divider';

interface Props {
  /** Accessible label for the trigger button. */
  label: string;
  trigger: ReactNode;
  items: MenuEntry[];
  align?: 'start' | 'end';
  placement?: 'below' | 'above';
  triggerClassName?: string;
  /** Shows the label as a tooltip on the trigger (off for triggers that already show text). */
  tooltip?: boolean;
  header?: ReactNode;
}

/** Accessible dropdown menu: click/Enter to open, arrow keys to move, Esc or outside click to close. */
export function Menu({
  label,
  trigger,
  items,
  align = 'end',
  placement = 'below',
  triggerClassName = 'icon-btn',
  tooltip = true,
  header,
}: Props) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const id = useId();

  const close = useCallback((refocus: boolean) => {
    setOpen(false);
    if (refocus) button.current?.focus();
  }, []);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) close(false);
    };
    document.addEventListener('mousedown', onDown);
    const raf = requestAnimationFrame(() =>
      root.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')?.focus(),
    );
    return () => {
      document.removeEventListener('mousedown', onDown);
      cancelAnimationFrame(raf);
    };
  }, [open, close]);

  const onKeyDown = (e: KeyboardEvent) => {
    if (!open) return;
    const els = [...(root.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])];
    const i = els.indexOf(document.activeElement as HTMLButtonElement);
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      close(true);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      els[(i + 1) % els.length]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      els[(i - 1 + els.length) % els.length]?.focus();
    } else if (e.key === 'Home') {
      e.preventDefault();
      els[0]?.focus();
    } else if (e.key === 'End') {
      e.preventDefault();
      els[els.length - 1]?.focus();
    } else if (e.key === 'Tab') {
      close(false);
    }
  };

  return (
    <div className="menu-root" ref={root} onKeyDown={onKeyDown}>
      <button
        ref={button}
        type="button"
        className={triggerClassName}
        aria-label={label}
        title={tooltip ? label : undefined}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen((o) => !o)}
      >
        {trigger}
      </button>
      {open && (
        <div id={id} role="menu" aria-label={label} className={`menu menu-${align} menu-${placement}`}>
          {header}
          {items.map((item, i) =>
            item === 'divider' ? (
              <div key={`d${i}`} className="menu-divider" role="separator" />
            ) : (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                className={`menu-item${item.danger ? ' danger' : ''}`}
                disabled={item.disabled}
                onClick={() => {
                  close(true);
                  item.onSelect();
                }}
              >
                <span className="menu-icon">{item.icon}</span>
                <span className="menu-label">{item.label}</span>
                {item.checked && (
                  <span className="menu-check" aria-label="selected">
                    ✓
                  </span>
                )}
                {item.hint && <kbd className="menu-hint">{item.hint}</kbd>}
              </button>
            ),
          )}
        </div>
      )}
    </div>
  );
}
