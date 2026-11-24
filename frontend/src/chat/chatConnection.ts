import { API_BASE_URL } from '../api/client';
import { useAuthStore } from '../stores/authStore';
import { useChatStore } from '../stores/chatStore';
import { emitChatEvent } from './chatEvents';
import { ChatSocket, socketUrl, type SocketFactory } from './chatSocket';

/** A token closer than this to its expiry is refreshed before CONNECT rather than refused by the server. */
const EXPIRY_MARGIN_MS = 60_000;

let createSocket: SocketFactory = (url) => new WebSocket(url, ['v12.stomp']);

/** Tests replace the browser WebSocket with a fake they drive frame by frame. */
export function setSocketFactory(factory: SocketFactory): void {
  createSocket = factory;
}

/** The app's one chat socket. ChatConnectionManager starts it on sign-in and stops it on sign-out. */
export const chatConnection = new ChatSocket({
  url: socketUrl(API_BASE_URL),
  createSocket: (url) => createSocket(url),
  getToken: tokenForSocket,
  onEvent: (event) => {
    useChatStore.getState().applyEvent(event);
    emitChatEvent(event);
  },
  onStatus: (status) => {
    useChatStore.getState().setStatus(status);
  },
});

/**
 * The stored access token, unless it is about to expire or the server just
 * refused one: then a refresh through the same single flight the Axios
 * interceptor uses. Null when there is no session any more, which stops
 * the socket; a refresh that got no answer throws, so the socket retries.
 */
async function tokenForSocket(fresh: boolean): Promise<string | null> {
  const state = useAuthStore.getState();
  if (state.status !== 'signedIn' || state.accessToken === null) {
    return null;
  }
  if (!fresh && !expiresWithin(state.accessToken, EXPIRY_MARGIN_MS)) {
    return state.accessToken;
  }
  const outcome = await state.refreshAccessToken();
  if (outcome.kind === 'refreshed') {
    return outcome.accessToken;
  }
  if (outcome.kind === 'expired') {
    return null;
  }
  throw new Error('The session could not be checked.');
}

/**
 * Reads exp from the token's payload. The client never trusts a token's
 * contents for anything but this timing hint; the server checks it anyway.
 * An unreadable token counts as not expiring, and the server decides.
 */
export function expiresWithin(token: string, marginMs: number, now = Date.now()): boolean {
  const payload = token.split('.')[1];
  if (payload === undefined) {
    return false;
  }
  try {
    const json: unknown = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
    if (typeof json === 'object' && json !== null && 'exp' in json && typeof json.exp === 'number') {
      return json.exp * 1000 - now < marginMs;
    }
  } catch {
    return false;
  }
  return false;
}
