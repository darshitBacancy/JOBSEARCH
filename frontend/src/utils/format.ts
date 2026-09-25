import type { SearchFilters } from '../types';

export const EMPLOYMENT_LABELS: Record<string, string> = {
  FULL_TIME: 'Full-time',
  CONTRACT: 'Contract',
  PART_TIME: 'Part-time',
  INTERNSHIP: 'Internship',
};

export function formatLpa(inr: number): string {
  const lpa = inr / 100000;
  return `₹${Number.isInteger(lpa) ? lpa : lpa.toFixed(1)} LPA`;
}

export function pct(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'n/a';
  return `${Math.round(value * 100)}%`;
}

export function formatTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });
}

/** Strips empty values so the backend only sees filters the user actually set. */
export function cleanFilters(f: SearchFilters): SearchFilters | null {
  const out: SearchFilters = {};
  if (f.remote) out.remote = true;
  if (f.location) out.location = f.location;
  if (f.salaryMin) out.salaryMin = f.salaryMin;
  if (f.experienceMin !== null && f.experienceMin !== undefined) out.experienceMin = f.experienceMin;
  if (f.experienceMax !== null && f.experienceMax !== undefined) out.experienceMax = f.experienceMax;
  if (f.employmentType) out.employmentType = f.employmentType;
  return Object.keys(out).length ? out : null;
}

export const SCORE_LABELS: Record<string, string> = {
  semantic: 'Semantic similarity',
  skills: 'Skill match',
  experience: 'Experience fit',
  location: 'Location',
  remote: 'Remote preference',
  salary: 'Salary',
};
