import { useEffect, useRef, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { JobDetail } from '../types';
import { formatDate } from '../utils/format';
import { useJobActions } from './JobActionsContext';
import { useSavedJobs } from '../hooks/useSavedJobs';
import { BookmarkIcon } from './BookmarkIcon';

interface Props {
  jobId: number;
  onClose: () => void;
}

function Section({ title, items }: { title: string; items: string[] }) {
  if (!items.length) return null;
  return (
    <section className="detail-section">
      <h4>{title}</h4>
      <ul>
        {items.map((it, i) => (
          <li key={i}>{it}</li>
        ))}
      </ul>
    </section>
  );
}

export function JobDetails({ jobId, onClose }: Props) {
  const { toggleCompare, isSelected, canSelectMore, askAbout } = useJobActions();
  const saved = useSavedJobs();
  const [job, setJob] = useState<JobDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let cancelled = false;
    setJob(null);
    setError(null);
    api
      .getJob(jobId)
      .then((j) => !cancelled && setJob(j))
      .catch((e) => !cancelled && setError(errorMessage(e)));
    return () => {
      cancelled = true;
    };
  }, [jobId, attempt]);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    document.body.classList.add('no-scroll');
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.classList.remove('no-scroll');
    };
  }, [onClose]);

  const selected = isSelected(jobId);

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="drawer" role="dialog" aria-modal="true" aria-labelledby="job-detail-title">
        <div className="drawer-head">
          <span className="drawer-kicker">Job #{jobId}</span>
          <button ref={closeRef} type="button" className="icon-btn" onClick={onClose} aria-label="Close job details">
            ✕
          </button>
        </div>

        {!job && !error && (
          <div className="drawer-body">
            <div className="skeleton title" />
            <div className="skeleton" />
            <div className="skeleton" />
            <div className="skeleton short" />
          </div>
        )}

        {error && (
          <div className="drawer-body">
            <div className="error-bubble" role="alert">
              <p>Couldn't load this job: {error}</p>
              <button type="button" className="btn btn-sm btn-ghost" onClick={() => setAttempt((a) => a + 1)}>
                ↻ Retry
              </button>
            </div>
          </div>
        )}

        {job && (
          <div className="drawer-body">
            <h2 id="job-detail-title">{job.title}</h2>
            <p className="detail-company">{job.company}</p>

            <dl className="detail-facts">
              <div>
                <dt>Location</dt>
                <dd>{job.location}</dd>
              </div>
              <div>
                <dt>Remote</dt>
                <dd>{job.remote ? 'Yes' : 'No'}</dd>
              </div>
              <div>
                <dt>Salary</dt>
                <dd>{job.salary}</dd>
              </div>
              <div>
                <dt>Experience</dt>
                <dd>{job.experience}</dd>
              </div>
              <div>
                <dt>Employment type</dt>
                <dd>{job.employmentTypeLabel || job.employmentType}</dd>
              </div>
              <div>
                <dt>Posted</dt>
                <dd>{formatDate(job.postedDate)}</dd>
              </div>
            </dl>

            <section className="detail-section">
              <h4>Skills</h4>
              <div className="skills">
                {job.skills.map((s) => (
                  <span key={s} className="skill">
                    {s}
                  </span>
                ))}
              </div>
            </section>

            <section className="detail-section">
              <h4>Description</h4>
              <p>{job.description}</p>
            </section>

            <Section title="Responsibilities" items={job.responsibilities} />
            <Section title="Requirements" items={job.requirements} />
            <Section title="Benefits" items={job.benefits} />

            <section className="detail-section">
              <h4>Application link</h4>
              <a href={job.applicationUrl} target="_blank" rel="noopener noreferrer" className="apply-link">
                {job.applicationUrl} ↗
              </a>
            </section>

            {job.dataNotice && <p className="data-notice">ℹ {job.dataNotice}</p>}
          </div>
        )}

        {job && (
          <div className="drawer-foot">
            <button
              type="button"
              className={`btn btn-ghost save-btn${saved.isSaved(job.id) ? ' saved' : ''}`}
              aria-pressed={saved.isSaved(job.id)}
              onClick={() => void saved.toggle(job)}
            >
              <BookmarkIcon filled={saved.isSaved(job.id)} /> {saved.isSaved(job.id) ? 'Saved' : 'Save'}
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={!selected && !canSelectMore}
              onClick={() => toggleCompare(job)}
            >
              {selected ? '✓ Added to compare' : 'Compare'}
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                askAbout(job);
                onClose();
              }}
            >
              Ask about this job
            </button>
          </div>
        )}
      </aside>
    </div>
  );
}
