import { describe, expect, it, vi } from 'vitest';
import type { ChatEvent } from '../api/chatApi';
import { chatMessage, CONVERSATION_ID, messageEvent } from '../test/chatFixtures';
import { FakeSocket, serverFrame } from '../test/fakeSocket';
import { ChatSocket, socketUrl, TYPING_INTERVAL_MS, type SocketStatus } from './chatSocket';

const URL = 'ws://api.test/api/v1/ws';

function harness(tokens: (string | null)[] = ['token-1']) {
  const sockets: FakeSocket[] = [];
  const events: ChatEvent[] = [];
  const statuses: SocketStatus[] = [];
  const timers: { run: () => void; ms: number }[] = [];
  const getToken = vi.fn<(fresh: boolean) => Promise<string | null>>(() => {
    return Promise.resolve(tokens.length > 1 ? (tokens.shift() ?? null) : (tokens[0] ?? null));
  });
  const socket = new ChatSocket({
    url: URL,
    createSocket: (url) => {
      const fake = new FakeSocket(url);
      sockets.push(fake);
      return fake;
    },
    getToken,
    onEvent: (event) => events.push(event),
    onStatus: (status) => statuses.push(status),
    timers: {
      set: (run, ms) => {
        timers.push({ run, ms });
        return timers.length;
      },
      clear: () => undefined,
    },
  });
  return { socket, sockets, events, statuses, timers, getToken };
}

/** Lets the getToken promise settle. */
async function settle() {
  await Promise.resolve();
  await Promise.resolve();
}

async function connected(h: ReturnType<typeof harness>) {
  h.socket.start();
  await settle();
  const fake = h.sockets.at(-1);
  if (fake === undefined) {
    throw new Error('no socket');
  }
  fake.open();
  fake.receive(serverFrame('CONNECTED', { version: '1.2' }));
  return fake;
}

describe('chat socket', () => {
  it('sends the token in CONNECT, never in the URL, then subscribes to the own queue only', async () => {
    const h = harness();
    const fake = await connected(h);

    expect(fake.url).toBe(URL);
    expect(fake.url).not.toContain('token');
    expect(fake.sent[0]).toMatch(/^CONNECT\n/);
    expect(fake.sent[0]).toContain('Authorization:Bearer token-1\n');
    expect(fake.sent[0]).toContain('accept-version:1.2\n');
    expect(fake.sent[1]).toBe('SUBSCRIBE\nid:chat\ndestination:/user/queue/chat\n\n\0');
    expect(fake.sent).toHaveLength(2);
    expect(h.statuses).toEqual(['connecting', 'online']);
  });

  it('delivers a well-formed pushed event', async () => {
    const h = harness();
    const fake = await connected(h);

    fake.receive(serverFrame('MESSAGE', { subscription: 'chat' }, JSON.stringify(messageEvent(chatMessage(4), 2))));

    expect(h.events).toHaveLength(1);
    expect(h.events[0]).toMatchObject({ type: 'MESSAGE', conversationId: CONVERSATION_ID, unreadCount: 2 });
  });

  it.each([
    ['not JSON', '<html>'],
    ['an unknown type', JSON.stringify({ type: 'ADMIN', conversationId: CONVERSATION_ID })],
    ['a message that contradicts itself', JSON.stringify(messageEvent(chatMessage(4, { kind: 'SYSTEM', body: 'x' })))],
    ['a negative unread count', JSON.stringify({ type: 'READ', conversationId: CONVERSATION_ID, unreadCount: -1 })],
  ])('drops a pushed frame that is %s', async (_name, body) => {
    const h = harness();
    const fake = await connected(h);

    fake.receive(serverFrame('MESSAGE', { subscription: 'chat' }, body));

    expect(h.events).toEqual([]);
  });

  it('ignores MESSAGE frames before the server has accepted CONNECT', async () => {
    const h = harness();
    h.socket.start();
    await settle();
    const fake = h.sockets[0];
    fake?.open();

    fake?.receive(serverFrame('MESSAGE', {}, JSON.stringify(messageEvent(chatMessage(1)))));

    expect(h.events).toEqual([]);
  });

  it('after a refused CONNECT, reconnects with a freshly refreshed token', async () => {
    const h = harness(['stale', 'fresh']);
    h.socket.start();
    await settle();
    const first = h.sockets[0];
    first?.open();
    first?.receive(serverFrame('ERROR', { message: 'Not allowed' }));
    first?.drop();

    expect(h.statuses.at(-1)).toBe('offline');
    expect(h.timers[0]?.ms).toBeGreaterThanOrEqual(1000);
    h.timers[0]?.run();
    await settle();

    expect(h.getToken).toHaveBeenNthCalledWith(1, false);
    expect(h.getToken).toHaveBeenNthCalledWith(2, true);
    h.sockets[1]?.open();
    expect(h.sockets[1]?.sent[0]).toContain('Authorization:Bearer fresh\n');
  });

  it('after a plain network drop, reconnects with waits that grow, without refreshing', async () => {
    const h = harness();
    const fake = await connected(h);

    fake.drop();
    h.timers[0]?.run();
    await settle();
    h.sockets[1]?.drop();

    expect(h.getToken).toHaveBeenLastCalledWith(false);
    expect(h.timers.map((timer) => timer.ms)).toHaveLength(2);
    expect(h.timers[1]?.ms ?? 0).toBeGreaterThan(h.timers[0]?.ms ?? 0);
  });

  it('stops for good when there is no session', async () => {
    const h = harness([null]);
    h.socket.start();
    await settle();

    expect(h.sockets).toHaveLength(0);
    expect(h.statuses.at(-1)).toBe('idle');
    expect(h.timers).toHaveLength(0);
  });

  it('stop closes the socket, says goodbye, and never reconnects', async () => {
    const h = harness();
    const fake = await connected(h);

    h.socket.stop();
    fake.drop();

    expect(fake.closedByClient).toBe(true);
    expect(fake.sent.at(-1)).toBe('DISCONNECT\n\n\0');
    expect(h.timers).toHaveLength(0);
    expect(h.statuses.at(-1)).toBe('idle');
  });

  it('sends typing notices at most once per interval per conversation, and none while offline', async () => {
    const h = harness();
    h.socket.sendTyping(CONVERSATION_ID, 0);
    const fake = await connected(h);

    h.socket.sendTyping(CONVERSATION_ID, 1_000);
    h.socket.sendTyping(CONVERSATION_ID, 1_500);
    h.socket.sendTyping(CONVERSATION_ID, 1_000 + TYPING_INTERVAL_MS);

    const typing = fake.sent.filter((frame) => frame.startsWith('SEND\n'));
    expect(typing).toHaveLength(2);
    expect(typing[0]).toBe(`SEND\ndestination:/app/conversations/${CONVERSATION_ID}/typing\n\n\0`);
  });
});

describe('socket URL', () => {
  it('follows the API base URL, switching to ws or wss', () => {
    expect(socketUrl('http://localhost:8080/api/v1')).toBe('ws://localhost:8080/api/v1/ws');
    expect(socketUrl('https://api.example.com/api/v1')).toBe('wss://api.example.com/api/v1/ws');
  });
});
