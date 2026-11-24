import { useEffect } from 'react';
import { chatConnection } from '../chat/chatConnection';
import { useAuthStore } from '../stores/authStore';
import { useChatStore } from '../stores/chatStore';

/**
 * Keeps the chat socket open exactly while someone is signed in, so a new
 * message reaches the nav badge on any screen. Signing out closes it and
 * forgets every unread count.
 */
export function ChatConnectionManager() {
  const signedIn = useAuthStore((state) => state.status === 'signedIn');

  useEffect(() => {
    if (!signedIn) {
      useChatStore.getState().reset();
      return;
    }
    chatConnection.start();
    return () => {
      chatConnection.stop();
    };
  }, [signedIn]);

  return null;
}
