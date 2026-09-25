import { createContext, useContext } from 'react';
import type { Feedback } from '../types';

/** Per-message actions (copy, regenerate, edit, feedback). Provided with `useChat().chatActions`. */
export interface ChatActions {
  loading: boolean;
  /** UI id of the newest assistant answer: only that one can be regenerated. */
  lastAssistantId: string | null;
  regenerate: () => void;
  editAndResend: (uiId: string, text: string) => void;
  setFeedback: (uiId: string, rating: Feedback) => void;
  copyText: (text: string) => void;
}

export const ChatActionsContext = createContext<ChatActions | null>(null);

/** Null outside the provider: messages then render without their action row. */
export function useChatActions(): ChatActions | null {
  return useContext(ChatActionsContext);
}
