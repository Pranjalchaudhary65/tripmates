import type { ChatEvent } from '../api/chatApi';

type Listener = (event: ChatEvent) => void;

const listeners = new Set<Listener>();

/** Screens that show a conversation listen here for what the socket pushes. Returns the unsubscribe. */
export function onChatEvent(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Called by the socket with an event that has already been validated. */
export function emitChatEvent(event: ChatEvent): void {
  for (const listener of [...listeners]) {
    listener(event);
  }
}
