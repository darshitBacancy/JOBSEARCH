import { createContext, useContext } from 'react';
import type { JobCard } from '../types';

export interface JobActions {
  openDetails: (jobId: number) => void;
  toggleCompare: (job: JobCard) => void;
  isSelected: (jobId: number) => boolean;
  canSelectMore: boolean;
  askAbout: (job: Pick<JobCard, 'id' | 'title'>) => void;
  sendSuggestion: (text: string) => void;
}

export const JobActionsContext = createContext<JobActions | null>(null);

export function useJobActions(): JobActions {
  const ctx = useContext(JobActionsContext);
  if (!ctx) throw new Error('useJobActions must be used inside JobActionsContext.Provider');
  return ctx;
}
