import { useContext, useEffect, useRef } from 'react';
import { useSavedJobs } from '../hooks/useSavedJobs';
import { JobActionsContext } from './JobActionsContext';
import '../chat-features.css';

interface Props {
  open: boolean;
  onClose: () => void;
}

/** Right-hand drawer listing bookmarked jobs. */
export function SavedJobsPanel({ open, onClose }: Props) {
  const { jobs, loaded, toggle, refresh } = useSavedJobs();
  const actions = useContext(JobActionsContext);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    void refresh();
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose, refresh]);

  return (
    <>
      <div className={`saved-backdrop${open ? ' show' : ''}`} onClick={onClose} aria-hidden="true" />
      <aside
        className={`saved-panel${open ? ' open' : ''}`}
        aria-label="Saved jobs"
        aria-hidden={!open}
        inert={!open ? true : undefined}
      >
        <header className="saved-head">
          <div>
            <h2>Saved jobs</h2>
            <p>{jobs.length ? `${jobs.length} bookmarked` : 'Bookmark jobs to find them here later'}</p>
          </div>
          <button ref={closeRef} type="button" className="icon-btn" onClick={onClose} aria-label="Close saved jobs">
            ×
          </button>
        </header>

        <div className="saved-body">
          {loaded && jobs.length === 0 && (
            <div className="saved-empty">
              <div aria-hidden="true">🔖</div>
              <p>No saved jobs yet.</p>
              <p className="muted">Tap the bookmark on any job card to save it.</p>
            </div>
          )}
          <ul className="saved-list">
            {jobs.map((job) => (
              <li key={job.id} className="saved-item">
                <button
                  type="button"
                  className="saved-main"
                  onClick={() => {
                    actions?.openDetails(job.id);
                  }}
                >
                  <strong>{job.title}</strong>
                  <span>
                    {job.company} · {job.location}
                    {job.remote ? ' · Remote' : ''}
                  </span>
                  <span className="saved-meta">
                    {job.salary} · {job.experience}
                  </span>
                </button>
                <button
                  type="button"
                  className="icon-btn sm danger"
                  onClick={() => void toggle(job)}
                  title="Remove from saved"
                  aria-label={`Remove ${job.title} from saved jobs`}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                    <path d="M18 6 6 18M6 6l12 12" />
                  </svg>
                </button>
              </li>
            ))}
          </ul>
        </div>
      </aside>
    </>
  );
}
