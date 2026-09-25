import type {
  ChatRequest,
  ChatResponse,
  Comparison,
  ConversationSummary,
  Feedback,
  ErrorResponse,
  JobCard,
  JobDetail,
  Page,
  StoredMessage,
  SystemStatus,
} from '../types';

const BASE = '/api';

export const BACKEND_DOWN_MESSAGE = "Can't reach the backend. Is the Spring Boot backend running (port 9091)?";

export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorResponse | null;

  constructor(message: string, status: number, body: ErrorResponse | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    if (init?.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    res = await fetch(BASE + path, {
      ...init,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...init?.headers },
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new ApiError(BACKEND_DOWN_MESSAGE, 0, null);
  }

  if (!res.ok) {
    let body: ErrorResponse | null = null;
    try {
      body = (await res.json()) as ErrorResponse;
    } catch {
      /* non-JSON error body */
    }
    // The Vite proxy answers 5xx without a JSON body when Spring Boot is not running.
    if (!body && res.status >= 500) {
      throw new ApiError(BACKEND_DOWN_MESSAGE, res.status, null);
    }
    const fieldMsg = body?.fieldErrors ? Object.values(body.fieldErrors).join(' ') : '';
    throw new ApiError(fieldMsg || body?.message || `Request failed (${res.status})`, res.status, body);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  chat: (body: ChatRequest, signal?: AbortSignal) =>
    request<ChatResponse>('/chat', { method: 'POST', body: JSON.stringify(body), signal }),

  regenerate: (conversationId: string, debug: boolean, signal?: AbortSignal) =>
    request<ChatResponse>('/chat/regenerate', {
      method: 'POST',
      body: JSON.stringify({ conversationId, debug }),
      signal,
    }),

  truncate: (conversationId: string, fromMessageId: number) =>
    request<void>(`/conversations/${encodeURIComponent(conversationId)}/truncate`, {
      method: 'POST',
      body: JSON.stringify({ fromMessageId }),
    }),

  feedback: (messageId: number, rating: Feedback) =>
    request<void>(`/messages/${messageId}/feedback`, { method: 'PUT', body: JSON.stringify({ rating }) }),

  jobCards: (jobIds: number[]) =>
    request<JobCard[]>('/jobs/cards', { method: 'POST', body: JSON.stringify({ jobIds }) }),

  savedJobs: () => request<JobCard[]>('/saved-jobs'),

  saveJob: (jobId: number) => request<void>(`/saved-jobs/${jobId}`, { method: 'PUT' }),

  unsaveJob: (jobId: number) => request<void>(`/saved-jobs/${jobId}`, { method: 'DELETE' }),

  clearConversations: () => request<void>('/conversations', { method: 'DELETE' }),

  getJob: (id: number) => request<JobDetail>(`/jobs/${id}`),

  compare: (jobIds: number[]) =>
    request<Comparison>('/jobs/compare', { method: 'POST', body: JSON.stringify({ jobIds }) }),

  listJobs: (page = 0, size = 20) => request<Page<JobCard>>(`/jobs?page=${page}&size=${size}`),

  status: () => request<SystemStatus>('/system/status'),

  conversations: () => request<ConversationSummary[]>('/conversations'),

  renameConversation: (conversationId: string, title: string) =>
    request<void>(`/conversations/${encodeURIComponent(conversationId)}`, {
      method: 'PUT',
      body: JSON.stringify({ title }),
    }),

  deleteConversation: (conversationId: string) =>
    request<void>(`/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' }),

  history: (conversationId: string) =>
    request<StoredMessage[]>(`/conversations/${encodeURIComponent(conversationId)}/messages`),
};

export function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError';
}

export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return 'Something went wrong.';
}
