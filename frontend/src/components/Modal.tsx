import { useEffect, useRef, type ReactNode } from 'react';
import { IconClose } from './Icons';

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  className?: string;
  footer?: ReactNode;
}

/** Native <dialog> modal: focus trapping, Esc to close and a backdrop come for free. */
export function Modal({ open, onClose, title, children, className = '', footer }: Props) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className={`modal ${className}`}
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onMouseDown={(e) => {
        // A press on the backdrop lands on the <dialog> element itself
        if (e.target === ref.current) onClose();
      }}
    >
      {open && (
        <div className="modal-card">
          <header className="modal-head">
            <h2>{title}</h2>
            <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">
              <IconClose />
            </button>
          </header>
          <div className="modal-body">{children}</div>
          {footer && <footer className="modal-foot">{footer}</footer>}
        </div>
      )}
    </dialog>
  );
}

export interface ConfirmRequest {
  title: string;
  message: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: () => void | Promise<void>;
}

export function ConfirmDialog({ request, onClose }: { request: ConfirmRequest | null; onClose: () => void }) {
  return (
    <Modal
      open={request !== null}
      onClose={onClose}
      title={request?.title ?? ''}
      className="modal-sm"
      footer={
        request && (
          <>
            <button type="button" className="btn btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              className={`btn ${request.danger ? 'btn-danger' : 'btn-primary'}`}
              autoFocus
              onClick={() => {
                onClose();
                void request.onConfirm();
              }}
            >
              {request.confirmLabel}
            </button>
          </>
        )
      }
    >
      <p className="confirm-text">{request?.message}</p>
    </Modal>
  );
}
