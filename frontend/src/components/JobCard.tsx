import { useState } from 'react';
import type { JobCard as JobCardModel } from '../types';
import { SCORE_LABELS, pct } from '../utils/format';
import { useJobActions } from './JobActionsContext';
import { useSavedJobs } from '../hooks/useSavedJobs';
import { BookmarkIcon } from './BookmarkIcon';

const MAX_SKILLS = 6;
const SCORE_TOOLTIP = 'Relative score from semantic similarity and your filters — not an objective rating';

function scoreTone(score: number): string {
  if (score >= 75) return 'strong';
  if (score >= 50) return 'good';
  return 'weak';
}

export function JobCard({ job, rank }: { job: JobCardModel; rank?: number }) {
  const { openDetails, toggleCompare, isSelected, canSelectMore, askAbout } = useJobActions();
  const { isSaved, toggle } = useSavedJobs();
  const saved = isSaved(job.id);
  const [expanded, setExpanded] = useState(false);
  const selected = isSelected(job.id);
  const extraSkills = job.skills.length - MAX_SKILLS;
  const hasWhy = job.matchReasons.length > 0 || job.gaps.length > 0;

  return (
    <article className={`job-card${selected ? ' selected' : ''}`} aria-label={`${job.title} at ${job.company}`}>
      <header className="job-card-head">
        <div className="job-card-title">
          <span className="company-tile" aria-hidden="true">
            {job.company.trim().charAt(0).toUpperCase()}
            {rank !== undefined && <span className="job-rank">{rank}</span>}
          </span>
          <div>
            <h3>
              <button type="button" className="link-btn" onClick={() => openDetails(job.id)}>
                {job.title}
              </button>
            </h3>
            <p className="job-company">
              {job.company} <span className="job-id">Job #{job.id}</span>
            </p>
          </div>
        </div>
        <div className="job-card-badges">
          {job.matchScore !== null && (
            <span className={`match-pill ${scoreTone(job.matchScore)}`} title={SCORE_TOOLTIP}>
              {Math.round(job.matchScore)}% match{job.matchLabel ? ` · ${job.matchLabel}` : ''}
            </span>
          )}
          <button
            type="button"
            className={`bookmark-btn${saved ? ' saved' : ''}`}
            onClick={() => void toggle(job)}
            aria-pressed={saved}
            aria-label={saved ? `Remove ${job.title} from saved jobs` : `Save ${job.title}`}
            title={saved ? 'Saved — click to remove' : 'Save job'}
          >
            <BookmarkIcon filled={saved} />
          </button>
        </div>
      </header>

      <ul className="job-meta" aria-label="Job facts">
        <li>📍 {job.location}</li>
        {job.remote && <li className="badge remote">Remote</li>}
        <li>🧭 {job.experience}</li>
        <li>💰 {job.salary}</li>
        <li>🗂 {job.employmentTypeLabel || job.employmentType}</li>
      </ul>

      <div className="skills" aria-label="Skills">
        {job.skills.slice(0, MAX_SKILLS).map((s) => (
          <span key={s} className="skill">
            {s}
          </span>
        ))}
        {extraSkills > 0 && (
          <span className="skill more" title={job.skills.slice(MAX_SKILLS).join(', ')}>
            +{extraSkills}
          </span>
        )}
      </div>

      {hasWhy && (
        <div className="why">
          <button type="button" className="why-toggle" aria-expanded={expanded} onClick={() => setExpanded((e) => !e)}>
            {expanded ? '▾' : '▸'} Why it matches
          </button>
          {expanded && (
            <div className="why-body">
              <ul className="reasons">
                {job.matchReasons.map((r) => (
                  <li key={r} className="ok">
                    <span aria-hidden="true">✓</span> {r}
                  </li>
                ))}
                {job.gaps.map((g) => (
                  <li key={g} className="gap">
                    <span aria-hidden="true">⚠</span> {g}
                  </li>
                ))}
              </ul>
              {job.scoreBreakdown && (
                <dl className="breakdown" title={SCORE_TOOLTIP}>
                  {Object.entries(job.scoreBreakdown).map(([k, v]) => (
                    <div key={k} className={v === null ? 'na' : ''}>
                      <dt>{SCORE_LABELS[k] ?? k}</dt>
                      <dd>
                        <span className="bar">
                          <span style={{ width: v === null ? 0 : `${Math.round(v * 100)}%` }} />
                        </span>
                        {pct(v)}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          )}
        </div>
      )}

      <footer className="job-actions">
        <button type="button" className="btn btn-primary btn-sm" onClick={() => openDetails(job.id)}>
          View details
        </button>
        <label className={`compare-toggle${!selected && !canSelectMore ? ' disabled' : ''}`}>
          <input
            type="checkbox"
            checked={selected}
            disabled={!selected && !canSelectMore}
            onChange={() => toggleCompare(job)}
          />
          Compare
        </label>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => askAbout(job)}>
          Ask about this job
        </button>
      </footer>
    </article>
  );
}
