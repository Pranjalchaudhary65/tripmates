// @vitest-environment jsdom
import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { emitChatEvent } from '../chat/chatEvents';
import { useAuthStore } from '../stores/authStore';
import { useChatStore } from '../stores/chatStore';
import { apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import {
  chatMessage,
  CONVERSATION_ID,
  conversationDetail,
  conversationSummary,
  messageEvent,
  OTHER_CONVERSATION_ID,
} from '../test/chatFixtures';
import { fakeSockets, serverFrame } from '../test/fakeSocket';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { pageOf } from '../test/tripFixtures';

const server = createApiServer();
const CONVERSATION_PATH = `/conversations/${CONVERSATION_ID}`;

let reads: number[] = [];

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  useChatStore.getState().reset();
  fakeSockets.clear();
  reads = [];
  server.use(
    apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')),
    apiPost(`${CONVERSATION_PATH}/read`, async ({ request }) => {
      const body = (await request.json()) as { upToSeq: number };
      reads.push(body.upToSeq);
      return new HttpResponse(null, { status: 204 });
    }),
    apiGet(`${CONVERSATION_PATH}/icebreakers`, () => HttpResponse.json({ suggestions: [] })),
  );
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

async function openConversation(detail: ReturnType<typeof conversationDetail>) {
  server.use(apiGet(CONVERSATION_PATH, () => HttpResponse.json(detail)));
  signIn();
  renderAt(`/messages/${CONVERSATION_ID}`);
  await screen.findByRole('heading', { level: 1, name: 'Asha' });
}

function messages(): HTMLElement {
  return screen.getByRole('list', { name: 'Messages' });
}

function present<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected a value.');
  }
  return value;
}

function typeMessage(text: string) {
  fireEvent.change(screen.getByLabelText('Message'), { target: { value: text } });
}

describe('messages inbox', () => {
  it('says how a chat starts when there are none', async () => {
    server.use(apiGet('/conversations', () => HttpResponse.json(pageOf([]))));
    signIn();
    renderAt('/messages');

    expect(await screen.findByText('No conversations yet.')).toBeDefined();
    expect(screen.getByRole('link', { name: 'Find a trip' })).toBeDefined();
  });

  it('lists conversations with unread counts, own last messages and flags', async () => {
    server.use(
      apiGet('/conversations', () =>
        HttpResponse.json(
          pageOf([
            conversationSummary({
              unreadCount: 2,
              lastMessageAt: '2026-11-24T09:30:00Z',
              lastMessage: { seq: 5, kind: 'USER', mine: false, preview: 'Send me 2000', systemCode: null, flag: 'MONEY_REQUEST' },
            }),
            conversationSummary({
              id: OTHER_CONVERSATION_ID,
              otherPerson: { firstName: 'Ravi', verificationTier: 'SELFIE_MATCH' },
              myRole: 'HOST',
              lastMessage: { seq: 1, kind: 'USER', mine: true, preview: 'Hi Ravi', systemCode: null, flag: null },
            }),
          ]),
        ),
      ),
    );
    signIn();
    renderAt('/messages');

    const asha = await screen.findByRole('link', { name: /Conversation with Asha about Monsoon weekend in Gokarna, 2 unread/ });
    expect(within(asha).getByText('Send me 2000')).toBeDefined();
    expect(within(asha).getByText('Flagged')).toBeDefined();
    const ravi = screen.getByRole('link', { name: /Conversation with Ravi/ });
    expect(within(ravi).getByText('You: Hi Ravi')).toBeDefined();
    expect(ravi.textContent).toContain('your trip');
    expect(screen.getByRole('link', { name: 'Messages, 2 unread' })).toBeDefined();
  });

  it('moves a conversation to the top when a message arrives', async () => {
    server.use(
      apiGet('/conversations', () =>
        HttpResponse.json(
          pageOf([
            conversationSummary({ id: OTHER_CONVERSATION_ID, otherPerson: { firstName: 'Ravi', verificationTier: 'EMAIL' } }),
            conversationSummary(),
          ]),
        ),
      ),
    );
    signIn();
    renderAt('/messages');
    await screen.findByRole('link', { name: /Conversation with Ravi/ });
    // Seeding runs in the same effect flush that subscribes to pushes.
    await waitFor(() => {
      expect(useChatStore.getState().unread).toHaveProperty(CONVERSATION_ID);
    });

    act(() => {
      emitChatEvent({
        type: 'MESSAGE',
        conversationId: CONVERSATION_ID,
        message: { ...chatMessage(3), body: 'Are we on?', kind: 'USER', systemCode: null, flag: null },
        unreadCount: 1,
      });
    });

    const rows = screen.getAllByRole('link', { name: /Conversation with/ });
    expect(rows[0]?.getAttribute('aria-label')).toContain('Asha');
    expect(within(present(rows[0])).getByText('Are we on?')).toBeDefined();
  });

  it('shows a failed load with a retry', async () => {
    server.use(apiGet('/conversations', () => problem(500, 'internal_error', 'boom')));
    signIn();
    renderAt('/messages');

    expect(await screen.findByRole('alert')).toBeDefined();
    expect(screen.getByRole('alert').textContent).not.toContain('boom');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
  });
});

describe('conversation screen', () => {
  it('renders bodies as text, never as markup', async () => {
    await openConversation(conversationDetail([chatMessage(1, { body: '<img src=x onerror="alert(1)">' })]));

    expect(within(messages()).getByText('<img src=x onerror="alert(1)">')).toBeDefined();
    expect(messages().querySelector('img')).toBeNull();
  });

  it('never calls the API for an ID that is not a UUID', async () => {
    signIn();
    renderAt('/messages/..%2Fadmin');

    expect(await screen.findByRole('heading', { name: 'Conversation not available' })).toBeDefined();
  });

  it('shows the same not-available screen for a 404', async () => {
    server.use(apiGet(CONVERSATION_PATH, () => problem(404, 'not_found', 'The requested resource was not found.')));
    signIn();
    renderAt(`/messages/${CONVERSATION_ID}`);

    expect(await screen.findByRole('heading', { name: 'Conversation not available' })).toBeDefined();
  });

  it('sends a message with a fresh client ID and shows it once stored', async () => {
    const sent: { clientMessageId: string; body: string }[] = [];
    server.use(
      apiPost(`${CONVERSATION_PATH}/messages`, async ({ request }) => {
        sent.push((await request.json()) as { clientMessageId: string; body: string });
        return HttpResponse.json(chatMessage(2, { mine: true, body: 'Slow trip please' }), { status: 201 });
      }),
    );
    await openConversation(conversationDetail([chatMessage(1)]));

    typeMessage('  Slow trip please  ');
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(await within(messages()).findByText('Slow trip please')).toBeDefined();
    expect(sent[0]).toMatchObject({ body: 'Slow trip please' });
    expect(sent[0]?.clientMessageId).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByLabelText<HTMLTextAreaElement>('Message').value).toBe('');
  });

  it('checks the message before sending anything', async () => {
    let posts = 0;
    server.use(
      apiPost(`${CONVERSATION_PATH}/messages`, () => {
        posts++;
        return HttpResponse.json(chatMessage(2, { mine: true }), { status: 201 });
      }),
    );
    await openConversation(conversationDetail([chatMessage(1)]));

    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(screen.getByText('Write something before sending.')).toBeDefined();
    typeMessage('a'.repeat(2001));
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(screen.getByText('Keep a message under 2000 characters. Split a longer one in two.')).toBeDefined();
    expect(posts).toBe(0);
  });

  it('retries a failed send with the same client ID, so it can never be stored twice', async () => {
    const ids: string[] = [];
    let first = true;
    server.use(
      apiPost(`${CONVERSATION_PATH}/messages`, async ({ request }) => {
        ids.push(((await request.json()) as { clientMessageId: string }).clientMessageId);
        if (first) {
          first = false;
          return problem(503, 'temporarily_unavailable', 'Try again shortly.');
        }
        return HttpResponse.json(chatMessage(2, { mine: true, body: 'hello' }), { status: 201 });
      }),
    );
    await openConversation(conversationDetail([chatMessage(1)]));

    typeMessage('hello');
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Try sending again' }));

    await waitFor(() => {
      expect(ids).toHaveLength(2);
    });
    expect(ids[1]).toBe(ids[0]);
    expect(await within(messages()).findByText('hello')).toBeDefined();
  });

  it('closes the composer when the server says the conversation is closed', async () => {
    server.use(
      apiPost(`${CONVERSATION_PATH}/messages`, () =>
        problem(409, 'conversation_closed', 'This conversation is closed.'),
      ),
    );
    await openConversation(conversationDetail([chatMessage(1)]));

    typeMessage('anyone there?');
    fireEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByText('This conversation is closed. You can still read it.')).toBeDefined();
    expect(screen.queryByLabelText('Message')).toBeNull();
    expect(screen.getByRole('alert').textContent).toContain('This conversation is closed.');
  });

  it('warns about a flagged message and notes redaction and erased text', async () => {
    await openConversation(
      conversationDetail([
        chatMessage(1, { body: 'Send me 2000 on gpay', flag: 'MONEY_REQUEST' }),
        chatMessage(2, { mine: true, body: 'call [contact hidden]', redacted: true, flag: 'OFF_PLATFORM_PUSH' }),
        chatMessage(3, { body: null }),
        chatMessage(4, { kind: 'SYSTEM', body: null, systemCode: 'CONTACT_UNLOCKED' }),
      ]),
    );

    const list = messages();
    expect(within(list).getByText(/TripMates never asks you to pay anyone/)).toBeDefined();
    expect(within(list).getByText(/Flagged for review: it looks like moving the conversation/)).toBeDefined();
    expect(within(list).getByText(/Contact details were hidden/)).toBeDefined();
    expect(within(list).getByText('This message is no longer available.')).toBeDefined();
    expect(within(list).getByText(/Your trip is locked. Contact details are now available/)).toBeDefined();
  });

  it('offers icebreakers on an empty conversation that only fill the box', async () => {
    let posts = 0;
    server.use(
      apiGet(`${CONVERSATION_PATH}/icebreakers`, () =>
        HttpResponse.json({ suggestions: ['What got you planning a trip to Gokarna?'] }),
      ),
      apiPost(`${CONVERSATION_PATH}/messages`, () => {
        posts++;
        return HttpResponse.json(chatMessage(1, { mine: true }), { status: 201 });
      }),
    );
    await openConversation(conversationDetail([]));

    expect(screen.getByText('No messages yet. Say hello to Asha.')).toBeDefined();
    fireEvent.click(await screen.findByRole('button', { name: 'What got you planning a trip to Gokarna?' }));

    expect(screen.getByLabelText<HTMLTextAreaElement>('Message').value).toBe(
      'What got you planning a trip to Gokarna?',
    );
    expect(posts).toBe(0);
  });

  it('keeps contact details hidden until the trip locks, then reveals them on request', async () => {
    server.use(
      apiGet(`${CONVERSATION_PATH}/contact`, () =>
        HttpResponse.json({ fullName: 'Asha Rao', email: 'asha@example.com', phone: null }),
      ),
    );
    await openConversation(conversationDetail([chatMessage(1)], { contactUnlocked: true }));

    const panel = screen.getByRole('region', { name: 'Contact details' });
    expect(within(panel).queryByText('asha@example.com')).toBeNull();
    fireEvent.click(within(panel).getByRole('button', { name: 'Show contact details' }));

    expect(await within(panel).findByText('asha@example.com')).toBeDefined();
    expect(within(panel).getByText('No verified phone number')).toBeDefined();
  });

  it('says contact details are hidden while the trip is not locked', async () => {
    await openConversation(conversationDetail([chatMessage(1)]));

    expect(screen.queryByRole('region', { name: 'Contact details' })).toBeNull();
    expect(screen.getByText(/Contact details stay hidden until your trip is locked/)).toBeDefined();
  });

  it('appends a pushed message, shows typing, and marks what it shows as read', async () => {
    await openConversation(conversationDetail([chatMessage(1)], { unreadCount: 1 }));
    await waitFor(() => {
      expect(reads).toContain(1);
    });

    act(() => {
      emitChatEvent({ type: 'TYPING', conversationId: CONVERSATION_ID });
    });
    expect(screen.getByText('Asha is typing…')).toBeDefined();
    act(() => {
      emitChatEvent({
        type: 'MESSAGE',
        conversationId: CONVERSATION_ID,
        message: { ...chatMessage(2), body: 'Just landed', kind: 'USER', systemCode: null, flag: null },
        unreadCount: 1,
      });
    });

    expect(within(messages()).getByText('Just landed')).toBeDefined();
    expect(screen.queryByText('Asha is typing…')).toBeNull();
    await waitFor(() => {
      expect(reads).toContain(2);
    });
  });

  it('refetches when a pushed message number skips ahead', async () => {
    let loads = 0;
    server.use(
      apiGet(CONVERSATION_PATH, () => {
        loads++;
        return HttpResponse.json(
          conversationDetail(loads === 1 ? [chatMessage(1)] : [chatMessage(1), chatMessage(2), chatMessage(3)]),
        );
      }),
    );
    signIn();
    renderAt(`/messages/${CONVERSATION_ID}`);
    await screen.findByRole('heading', { level: 1, name: 'Asha' });
    // Marking read runs in the same effect flush that subscribes to pushes.
    await waitFor(() => {
      expect(reads).toContain(1);
    });

    act(() => {
      emitChatEvent({
        type: 'MESSAGE',
        conversationId: CONVERSATION_ID,
        message: { ...chatMessage(3), kind: 'USER', systemCode: null, flag: null },
        unreadCount: 2,
      });
    });

    expect(await within(messages()).findByText('Message 2')).toBeDefined();
    expect(loads).toBe(2);
  });

  it('loads earlier messages above the current ones', async () => {
    server.use(
      apiGet(`${CONVERSATION_PATH}/messages`, ({ request }) => {
        expect(new URL(request.url).searchParams.get('before')).toBe('51');
        return HttpResponse.json({ items: [chatMessage(49), chatMessage(50)], hasOlder: false });
      }),
    );
    await openConversation(conversationDetail([chatMessage(51), chatMessage(52)], {}, true));

    fireEvent.click(screen.getByRole('button', { name: 'Show earlier messages' }));

    await within(messages()).findByText('Message 49');
    const items = within(messages()).getAllByRole('listitem').map((item) => item.textContent);
    expect(items[0]).toContain('Message 49');
    expect(screen.queryByRole('button', { name: 'Show earlier messages' })).toBeNull();
  });
});

describe('live connection', () => {
  it('opens one socket on sign-in and counts unread messages in the nav as they arrive', async () => {
    server.use(apiGet('/trips', () => HttpResponse.json(pageOf([]))));
    signIn();
    renderAt('/trips');
    await waitFor(() => {
      expect(fakeSockets.all()).toHaveLength(1);
    });
    const socket = fakeSockets.last();

    act(() => {
      socket?.open();
      socket?.receive(serverFrame('CONNECTED', { version: '1.2' }));
      socket?.receive(serverFrame('MESSAGE', { subscription: 'chat' }, JSON.stringify(messageEvent(chatMessage(5), 3))));
    });

    expect(await screen.findByRole('link', { name: 'Messages, 3 unread' })).toBeDefined();
    expect(socket?.sent[0]).toMatch(/^CONNECT\n/);
  });

  it('closes the socket and forgets unread counts on sign-out', async () => {
    server.use(apiGet('/trips', () => HttpResponse.json(pageOf([]))));
    signIn();
    renderAt('/trips');
    await waitFor(() => {
      expect(fakeSockets.all()).toHaveLength(1);
    });
    act(() => {
      useChatStore.getState().setUnread(CONVERSATION_ID, 4);
    });

    act(() => {
      useAuthStore.getState().clearSession();
    });

    await waitFor(() => {
      expect(fakeSockets.last()?.closedByClient).toBe(true);
    });
    expect(useChatStore.getState().unread).toEqual({});
  });
});
