import { TRIP_ID } from './tripFixtures';

export const CONVERSATION_ID = '3a4b5c6d-7e8f-4a0b-9c1d-2e3f4a5b6c7d';
export const OTHER_CONVERSATION_ID = '4b5c6d7e-8f90-4b1c-8d2e-3f4a5b6c7d8e';
export const MATCH_ID = '5c6d7e8f-9001-4c2d-9e3f-4a5b6c7d8e9f';

/** ChatMessageResponse as the backend writes it: a message from the other person by default. */
export function chatMessage(seq: number, overrides: Record<string, unknown> = {}) {
  return {
    id: `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
    seq,
    kind: 'USER',
    mine: false,
    body: `Message ${String(seq)}`,
    systemCode: null,
    redacted: false,
    flag: null,
    sentAt: '2026-11-24T09:30:00Z',
    ...overrides,
  };
}

/** ConversationSummaryResponse: the caller is the companion, chatting with host Asha. */
export function conversationSummary(overrides: Record<string, unknown> = {}) {
  return {
    id: CONVERSATION_ID,
    matchId: MATCH_ID,
    trip: {
      id: TRIP_ID,
      title: 'Monsoon weekend in Gokarna',
      destinationName: 'Gokarna, Karnataka',
      startDate: '2026-12-12',
      endDate: '2026-12-14',
      status: 'OPEN',
    },
    otherPerson: { firstName: 'Asha', verificationTier: 'PHONE' },
    myRole: 'COMPANION',
    canSend: true,
    contactUnlocked: false,
    unreadCount: 0,
    lastMessageAt: null,
    lastMessage: null,
    createdAt: '2026-11-20T08:00:00Z',
    ...overrides,
  };
}

export function conversationDetail(
  messages: readonly ReturnType<typeof chatMessage>[] = [],
  summary: Record<string, unknown> = {},
  hasOlder = false,
) {
  return { conversation: conversationSummary(summary), messages, hasOlder };
}

/** A ChatEventResponse as the socket pushes it, in a STOMP MESSAGE frame body. */
export function messageEvent(message: ReturnType<typeof chatMessage>, unreadCount = 1, conversationId = CONVERSATION_ID) {
  return { type: 'MESSAGE', conversationId, message, unreadCount };
}
