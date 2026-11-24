import { readChatEvent, type ChatEvent } from '../api/chatApi';
import { decodeFrame, encodeFrame } from './stompFrames';

export type SocketStatus = 'idle' | 'connecting' | 'online' | 'offline';

/** The slice of the browser WebSocket the chat uses, so tests can stand in for it. */
export interface SocketLike {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onmessage: ((event: MessageEvent) => void) | null;
  onclose: ((event: CloseEvent) => void) | null;
  onerror: ((event: Event) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export type SocketFactory = (url: string) => SocketLike;

export interface ChatSocketOptions {
  url: string;
  createSocket: SocketFactory;
  /**
   * An access token for CONNECT. `fresh` asks for a newly refreshed one,
   * after the server refused the last. Null means there is no session, and
   * the socket stops trying.
   */
  getToken: (fresh: boolean) => Promise<string | null>;
  onEvent: (event: ChatEvent) => void;
  onStatus: (status: SocketStatus) => void;
  /** For tests; defaults to the real timers. */
  timers?: { set: (run: () => void, ms: number) => unknown; clear: (handle: unknown) => void };
}

const OPEN = 1;

export const CHAT_QUEUE = '/user/queue/chat';

/** Waits before reconnect attempts 1, 2, 3...; the last repeats. Jitter keeps many tabs from retrying in step. */
export const RECONNECT_DELAYS_MS = [1_000, 2_000, 5_000, 10_000, 30_000] as const;

/** At most one typing notice per conversation per this long; the server cuts off a socket that floods frames. */
export const TYPING_INTERVAL_MS = 3_000;

/**
 * The chat's one socket: STOMP over a plain WebSocket, receive-only apart
 * from typing notices. Messages are sent over HTTP.
 *
 * - The access token goes in the CONNECT frame, never in the URL, where it
 *   would end up in server and proxy logs.
 * - It subscribes to the caller's own queue only; the server decides what
 *   reaches it.
 * - Every pushed frame is parsed strictly (decodeFrame, then readChatEvent);
 *   anything malformed is dropped.
 * - When the socket closes it reconnects with growing waits. The server
 *   closes it on purpose when the access token expires; getToken hands over
 *   a refreshed token when the stored one is about to expire, and a CONNECT
 *   the server refused with an ERROR is retried with a freshly refreshed
 *   one. A plain network drop does not refresh anything.
 */
export class ChatSocket {
  private socket: SocketLike | null = null;

  private status: SocketStatus = 'idle';

  private running = false;

  private attempt = 0;

  private needsFreshToken = false;

  private reconnectTimer: unknown = null;

  private readonly lastTyping = new Map<string, number>();

  private readonly timers: NonNullable<ChatSocketOptions['timers']>;

  private readonly options: ChatSocketOptions;

  constructor(options: ChatSocketOptions) {
    this.options = options;
    this.timers = options.timers ?? {
      set: (run, ms) => setTimeout(run, ms),
      clear: (handle) => {
        clearTimeout(handle as ReturnType<typeof setTimeout>);
      },
    };
  }

  start(): void {
    if (this.running) {
      return;
    }
    this.running = true;
    this.attempt = 0;
    void this.connect();
  }

  stop(): void {
    this.running = false;
    if (this.reconnectTimer !== null) {
      this.timers.clear(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const socket = this.socket;
    this.socket = null;
    if (socket !== null) {
      socket.onclose = null;
      if (socket.readyState === OPEN) {
        socket.send(encodeFrame('DISCONNECT', {}));
      }
      socket.close(1000, 'Signed out');
    }
    this.setStatus('idle');
  }

  /** Tells the other person in the conversation that the caller is typing; throttled, dropped while offline. */
  sendTyping(conversationId: string, now = Date.now()): void {
    if (this.status !== 'online' || this.socket === null) {
      return;
    }
    const last = this.lastTyping.get(conversationId);
    if (last !== undefined && now - last < TYPING_INTERVAL_MS) {
      return;
    }
    this.lastTyping.set(conversationId, now);
    this.socket.send(
      encodeFrame('SEND', { destination: `/app/conversations/${encodeURIComponent(conversationId)}/typing` }),
    );
  }

  getStatus(): SocketStatus {
    return this.status;
  }

  private async connect(): Promise<void> {
    const fresh = this.needsFreshToken;
    this.needsFreshToken = false;
    this.setStatus('connecting');
    let token: string | null;
    try {
      token = await this.options.getToken(fresh);
    } catch {
      token = null;
      this.scheduleReconnect();
      return;
    }
    if (!this.running) {
      return;
    }
    if (token === null) {
      this.running = false;
      this.setStatus('idle');
      return;
    }
    const socket = this.options.createSocket(this.options.url);
    this.socket = socket;
    let connected = false;
    socket.onopen = () => {
      socket.send(
        encodeFrame('CONNECT', {
          'accept-version': '1.2',
          host: new URL(this.options.url).host,
          'heart-beat': '0,0',
          Authorization: `Bearer ${token}`,
        }),
      );
    };
    socket.onmessage = (event) => {
      const frame = decodeFrame(event.data);
      if (frame === null || this.socket !== socket) {
        return;
      }
      if (frame.command === 'CONNECTED') {
        connected = true;
        this.attempt = 0;
        socket.send(encodeFrame('SUBSCRIBE', { id: 'chat', destination: CHAT_QUEUE }));
        this.setStatus('online');
      } else if (frame.command === 'MESSAGE' && connected) {
        let parsed: unknown;
        try {
          parsed = JSON.parse(frame.body);
        } catch {
          return;
        }
        const chatEvent = readChatEvent(parsed);
        if (chatEvent !== null) {
          this.options.onEvent(chatEvent);
        }
      } else if (frame.command === 'ERROR' && !connected) {
        this.needsFreshToken = true;
      }
    };
    socket.onerror = () => {
      // A close always follows; reconnecting is handled there.
    };
    socket.onclose = () => {
      if (this.socket !== socket) {
        return;
      }
      this.socket = null;
      this.scheduleReconnect();
    };
  }

  private scheduleReconnect(): void {
    if (!this.running) {
      return;
    }
    this.setStatus('offline');
    const base = RECONNECT_DELAYS_MS[Math.min(this.attempt, RECONNECT_DELAYS_MS.length - 1)] ?? 30_000;
    this.attempt++;
    const delay = base + Math.floor(Math.random() * 250);
    this.reconnectTimer = this.timers.set(() => {
      this.reconnectTimer = null;
      if (this.running) {
        void this.connect();
      }
    }, delay);
  }

  private setStatus(status: SocketStatus): void {
    if (this.status !== status) {
      this.status = status;
      this.options.onStatus(status);
    }
  }
}

/** ws:// or wss:// for the API's own origin and path, so the socket follows wherever the API is configured. */
export function socketUrl(apiBaseUrl: string): string {
  const url = new URL(`${apiBaseUrl}/ws`);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.href;
}
