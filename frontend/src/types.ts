export type Intent = 'NEW_SEARCH' | 'REFINE' | 'COMPARE' | 'JOB_QUESTION' | 'GENERAL';

export interface SearchFilters {
  remote?: boolean | null;
  location?: string | null;
  /** Annual salary in INR */
  salaryMin?: number | null;
  experienceMin?: number | null;
  experienceMax?: number | null;
  employmentType?: string | null;
}

export interface ChatRequest {
  conversationId: string | null;
  message: string;
  selectedJobIds: number[] | null;
  focusJobId: number | null;
  filters: SearchFilters | null;
  debug: boolean;
}

export type ScoreBreakdown = Record<string, number | null>;

export interface JobCard {
  id: number;
  title: string;
  company: string;
  location: string;
  remote: boolean;
  employmentType: string;
  employmentTypeLabel: string;
  experience: string;
  experienceMin: number;
  experienceMax: number;
  salary: string;
  salaryMin: number;
  salaryMax: number;
  currency: string;
  skills: string[];
  postedDate: string;
  matchScore: number | null;
  matchLabel: string | null;
  matchReasons: string[];
  gaps: string[];
  scoreBreakdown: ScoreBreakdown | null;
}

export interface JobDetail extends JobCard {
  description: string;
  requirements: string[];
  responsibilities: string[];
  benefits: string[];
  applicationUrl: string;
  dataNotice: string;
}

export interface ComparisonRow {
  label: string;
  values: string[];
}

export interface Comparison {
  jobs: JobCard[];
  rows: ComparisonRow[];
  summary: string;
}

export interface JobSearchCriteria {
  keywords: string[];
  skills: string[];
  location: string | null;
  remote: boolean | null;
  experienceMin: number | null;
  experienceMax: number | null;
  salaryMin: number | null;
  salaryMax: number | null;
  employmentType: string | null;
}

export interface SourceRef {
  jobId: number;
  title: string;
  company: string;
  sections: string[];
  similarity: number | null;
}

export interface RetrievedChunk {
  chunkId: string;
  jobId: number;
  section: string;
  similarity: number;
  preview: string;
}

export interface SelectedJobTrace {
  jobId: number;
  title: string;
  company: string;
  score: number;
  breakdown: ScoreBreakdown;
}

export interface ToolCallRecord {
  name: string;
  arguments: string;
  summary: string;
  durationMs: number;
  ok: boolean;
}

export interface RagTrace {
  traceId: string;
  timestamp: string;
  userQuery: string;
  intent: string;
  criteriaSource: string;
  criteria: JobSearchCriteria | null;
  embeddingProvider: string;
  embeddingModel: string;
  structuredCandidateCount: number;
  retrievedChunks: RetrievedChunk[];
  selectedJobs: SelectedJobTrace[];
  relaxedFilters: string[];
  contextSentToLlm: string | null;
  llmModel: string | null;
  answerSource: string;
  finalAnswer: string;
  timingsMs: Record<string, number>;
  notes: string[];
  toolCalls?: ToolCallRecord[];
}

export interface ChatResponse {
  conversationId: string;
  messageId: number;
  message: string;
  intent: Intent;
  jobs: JobCard[];
  totalMatches: number;
  sources: SourceRef[];
  comparison: Comparison | null;
  criteria: JobSearchCriteria | null;
  relaxedFilters: string[];
  aiAvailable: boolean;
  notice: string | null;
  focusJobId: number | null;
  suggestions: string[];
  toolCalls?: ToolCallRecord[];
  debug: RagTrace | null;
  timestamp: string;
  /** Stored ids of the user message and this answer (used for edit / feedback). */
  userMessageId?: number;
  assistantMessageId?: number;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface IndexStatus {
  indexed: boolean;
  jobCount: number;
  chunkCount: number;
  embeddingProvider: string;
  embeddingModel: string;
  dimensions: number;
  indexedAt: string | null;
}

export interface SystemStatus {
  aiConfigured: boolean;
  /** Health of the most recent LLM call: ONLINE | UNAVAILABLE | NOT_CONFIGURED | UNKNOWN */
  aiStatus: string;
  aiLastError: string | null;
  chatModel: string;
  embeddingProvider: string;
  embeddingModel: string;
  vectorStore?: string;
  debugEnabled: boolean;
  index: IndexStatus;
}

export interface ConversationSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
}

export interface StoredMessage {
  id: number;
  role: 'USER' | 'ASSISTANT';
  message: string;
  jobIds: number[];
  timestamp: string;
  feedback?: Feedback;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  fieldErrors: Record<string, string> | null;
}

export type Feedback = 'up' | 'down' | null;

/** A message as rendered in the UI. */
export type UiMessage =
  | { kind: 'user'; id: string; text: string; timestamp: string; serverId?: number }
  | { kind: 'assistant'; id: string; response: ChatResponse; feedback?: Feedback }
  | {
      kind: 'history';
      id: string;
      role: 'USER' | 'ASSISTANT';
      text: string;
      timestamp: string;
      serverId: number;
      jobIds: number[];
      jobs?: JobCard[];
      feedback?: Feedback;
    }
  | { kind: 'error'; id: string; text: string; retryMessage: string | null }
  | { kind: 'stopped'; id: string }
  | { kind: 'comparison'; id: string; comparison: Comparison };
