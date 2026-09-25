import type { JobCard as JobCardModel } from '../types';
import { JobCard } from './JobCard';

export function JobList({ jobs, totalMatches }: { jobs: JobCardModel[]; totalMatches?: number }) {
  if (!jobs.length) return null;
  return (
    <section className="job-list" aria-label="Matching jobs">
      {totalMatches !== undefined && totalMatches > jobs.length && (
        <p className="job-list-note">
          Showing top {jobs.length} of {totalMatches} matching jobs
        </p>
      )}
      {jobs.map((job, i) => (
        <JobCard key={job.id} job={job} rank={i + 1} />
      ))}
    </section>
  );
}
