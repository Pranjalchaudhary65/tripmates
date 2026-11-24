import type { SocketLike } from '../chat/chatSocket';

/**
 * Stands in for the browser WebSocket. It never touches the network: a test
 * opens it, feeds it server frames and closes it, and reads what the client
 * sent.
 */
export class FakeSocket implements SocketLike {
  readyState = 0;

  onopen: ((event: Event) => void) | null = null;

  onmessage: ((event: MessageEvent) => void) | null = null;

  onclose: ((event: CloseEvent) => void) | null = null;

  onerror: ((event: Event) => void) | null = null;

  readonly sent: string[] = [];

  closedByClient = false;

  readonly url: string;

  constructor(url: string) {
    this.url = url;
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.readyState = 3;
    this.closedByClient = true;
  }

  /** The handshake completed. */
  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  /** One frame from the server. */
  receive(data: string): void {
    this.onmessage?.(new MessageEvent('message', { data }));
  }

  /** The server or the network closed it. */
  drop(): void {
    this.readyState = 3;
    this.onclose?.(new Event('close') as CloseEvent);
  }
}

const created: FakeSocket[] = [];

export const fakeSockets = {
  create(url: string): FakeSocket {
    const socket = new FakeSocket(url);
    created.push(socket);
    return socket;
  },
  all(): readonly FakeSocket[] {
    return created;
  },
  last(): FakeSocket | undefined {
    return created.at(-1);
  },
  clear(): void {
    created.length = 0;
  },
};

/** A server frame as Spring writes it. */
export function serverFrame(command: string, headers: Record<string, string>, body = ''): string {
  const lines = Object.entries(headers).map(([name, value]) => `${name}:${value}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}
