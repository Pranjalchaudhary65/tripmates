import { create } from 'zustand';
import type { ChatEvent } from '../api/chatApi';
import type { SocketStatus } from '../chat/chatSocket';

export interface ChatState {
  /** The live connection, for the "reconnecting" notice. */
  status: SocketStatus;
  /** Unread messages per conversation the client has heard about, for the nav badge. */
  unread: Readonly<Record<string, number>>;
  setStatus: (status: SocketStatus) => void;
  setUnread: (conversationId: string, count: number) => void;
  applyEvent: (event: ChatEvent) => void;
  reset: () => void;
}

/**
 * Chat state shared across screens. Memory only, like the auth store: unread
 * counts are not worth a storage key any script on the page could read, and
 * they are rebuilt from the server on the next visit to the inbox.
 */
export const useChatStore = create<ChatState>()((set) => ({
  status: 'idle',
  unread: {},

  setStatus: (status) => {
    set({ status });
  },

  setUnread: (conversationId, count) => {
    set((state) => ({ unread: { ...state.unread, [conversationId]: count } }));
  },

  applyEvent: (event) => {
    if (event.type === 'MESSAGE' || event.type === 'READ') {
      set((state) => ({ unread: { ...state.unread, [event.conversationId]: event.unreadCount } }));
    }
  },

  reset: () => {
    set({ status: 'idle', unread: {} });
  },
}));

/** Total unread messages across the conversations the client knows about. */
export function selectUnreadTotal(state: ChatState): number {
  return Object.values(state.unread).reduce((total, count) => total + count, 0);
}
