import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { chatMessage, CONVERSATION_ID, conversationDetail, conversationSummary } from '../test/chatFixtures';
import { pageOf } from '../test/tripFixtures';
import { createChatApi, readChatEvent } from './chatApi';
import { createApiClient } from './createApiClient';

const BASE_URL = 'http://api.test/api/v1';

const server = setupServer();
const api = createChatApi(createApiClient(BASE_URL));

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

describe('chat API', () => {
  it('reads a conversation with its messages oldest first', async () => {
    server.use(
      http.get(`${BASE_URL}/conversations/${CONVERSATION_ID}`, () =>
        HttpResponse.json(conversationDetail([chatMessage(1, { mine: true }), chatMessage(2)], {}, true)),
      ),
    );

    const detail = await api.conversation(CONVERSATION_ID);

    expect(detail.conversation.otherPerson.firstName).toBe('Asha');
    expect(detail.messages.map((message) => message.seq)).toEqual([1, 2]);
    expect(detail.messages[0]?.mine).toBe(true);
    expect(detail.hasOlder).toBe(true);
  });

  it('sends a message with its client ID and reads back the stored one', async () => {
    let sent: unknown = null;
    server.use(
      http.post(`${BASE_URL}/conversations/${CONVERSATION_ID}/messages`, async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(chatMessage(3, { mine: true, body: 'Hello [contact hidden]', redacted: true }), {
          status: 201,
        });
      }),
    );

    const message = await api.send(CONVERSATION_ID, 'client-1', 'Hello 9876543210');

    expect(sent).toEqual({ clientMessageId: 'client-1', body: 'Hello 9876543210' });
    expect(message.redacted).toBe(true);
    expect(message.body).toBe('Hello [contact hidden]');
  });

  it('reads the inbox page', async () => {
    server.use(
      http.get(`${BASE_URL}/conversations`, () =>
        HttpResponse.json(
          pageOf([
            conversationSummary({
              unreadCount: 2,
              lastMessage: { seq: 5, kind: 'USER', mine: false, preview: 'See you', systemCode: null, flag: null },
            }),
          ]),
        ),
      ),
    );

    const page = await api.inbox(0);

    expect(page.items[0]?.unreadCount).toBe(2);
    expect(page.items[0]?.lastMessage?.preview).toBe('See you');
  });

  it.each([
    ['a SYSTEM message with text', chatMessage(1, { kind: 'SYSTEM', systemCode: 'CONVERSATION_CLOSED', body: 'x' })],
    ['a USER message with a system code', chatMessage(1, { systemCode: 'CONTACT_UNLOCKED' })],
    ['a flag on a SYSTEM message', chatMessage(1, { kind: 'SYSTEM', systemCode: 'CONVERSATION_CLOSED', body: null, flag: 'HARASSMENT' })],
    ['an unknown flag', chatMessage(1, { flag: 'SPAM' })],
    ['mine that is not a boolean', chatMessage(1, { mine: 'yes' })],
    ['a zero seq', chatMessage(0)],
  ])('refuses %s', async (_name, message) => {
    server.use(
      http.get(`${BASE_URL}/conversations/${CONVERSATION_ID}`, () => HttpResponse.json(conversationDetail([message]))),
    );

    await expect(api.conversation(CONVERSATION_ID)).rejects.toThrow();
  });

  it('refuses messages out of order', async () => {
    server.use(
      http.get(`${BASE_URL}/conversations/${CONVERSATION_ID}`, () =>
        HttpResponse.json(conversationDetail([chatMessage(2), chatMessage(1)])),
      ),
    );

    await expect(api.conversation(CONVERSATION_ID)).rejects.toThrow('Messages out of order.');
  });

  it('reads contact details, with no phone when none is verified', async () => {
    server.use(
      http.get(`${BASE_URL}/conversations/${CONVERSATION_ID}/contact`, () =>
        HttpResponse.json({ fullName: 'Asha Rao', email: 'asha@example.com', phone: null }),
      ),
    );

    expect(await api.contact(CONVERSATION_ID)).toEqual({ fullName: 'Asha Rao', email: 'asha@example.com', phone: null });
  });
});

describe('pushed chat events', () => {
  it('reads the three event types', () => {
    expect(readChatEvent({ type: 'TYPING', conversationId: CONVERSATION_ID, message: null, unreadCount: null })).toEqual({
      type: 'TYPING',
      conversationId: CONVERSATION_ID,
    });
    expect(readChatEvent({ type: 'READ', conversationId: CONVERSATION_ID, message: null, unreadCount: 0 })).toEqual({
      type: 'READ',
      conversationId: CONVERSATION_ID,
      unreadCount: 0,
    });
    expect(
      readChatEvent({ type: 'MESSAGE', conversationId: CONVERSATION_ID, message: chatMessage(7), unreadCount: 3 }),
    ).toMatchObject({ type: 'MESSAGE', unreadCount: 3 });
  });

  it.each([
    ['null', null],
    ['no conversation', { type: 'TYPING' }],
    ['a MESSAGE without a message', { type: 'MESSAGE', conversationId: CONVERSATION_ID, message: null, unreadCount: 1 }],
    ['a MESSAGE without an unread count', { type: 'MESSAGE', conversationId: CONVERSATION_ID, message: chatMessage(1) }],
    ['an unknown type', { type: 'DELETE', conversationId: CONVERSATION_ID }],
  ])('drops %s', (_name, raw) => {
    expect(readChatEvent(raw)).toBeNull();
  });
});
