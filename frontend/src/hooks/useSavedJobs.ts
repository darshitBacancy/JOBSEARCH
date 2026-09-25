import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { api, errorMessage } from '../api/client';
import { showToast } from '../components/Toast';
import type { JobCard } from '../types';

/** Module-level store so every component shares one saved-jobs list. */
interface State {
  jobs: JobCard[];
  ids: Set<number>;
  loaded: boolean;
}

let state: State = { jobs: [], ids: new Set(), loaded: false };
const listeners = new Set<() => void>();
let loading: Promise<void> | null = null;

function set(jobs: JobCard[], loaded = true) {
  state = { jobs, ids: new Set(jobs.map((j) => j.id)), loaded };
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function refresh(): Promise<void> {
  loading ??= api
    .savedJobs()
    .then((jobs) => set(jobs ?? []))
    .catch(() => {
      // Saved jobs are optional: an older backend without the endpoint just shows none.
      set(state.jobs, true);
    })
    .finally(() => {
      loading = null;
    });
  return loading;
}

async function toggle(job: JobCard) {
  const wasSaved = state.ids.has(job.id);
  const before = state.jobs;
  set(wasSaved ? before.filter((j) => j.id !== job.id) : [job, ...before]);
  try {
    if (wasSaved) await api.unsaveJob(job.id);
    else await api.saveJob(job.id);
    showToast(wasSaved ? 'Removed from saved jobs' : 'Job saved', 'success');
  } catch (err) {
    set(before);
    showToast(`Couldn't ${wasSaved ? 'remove' : 'save'} job: ${errorMessage(err)}`, 'error');
  }
}

export function useSavedJobs() {
  const snap = useSyncExternalStore(subscribe, () => state);

  useEffect(() => {
    if (!state.loaded) void refresh();
  }, []);

  const isSaved = useCallback((id: number) => snap.ids.has(id), [snap]);

  return {
    ids: snap.ids,
    jobs: snap.jobs,
    savedCount: snap.jobs.length,
    loaded: snap.loaded,
    isSaved,
    toggle,
    refresh,
  };
}
