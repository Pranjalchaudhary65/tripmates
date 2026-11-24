import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord, type VerificationTier } from './accessGrant';
import { apiClient } from './client';
import { TRIP_STATUSES, type TripStatus } from './tripsApi';
import type { Page } from './verificationApi';

export const MESSAGE_KINDS = ['USER', 'SYSTEM'] as const;
export const SYSTEM_CODES = ['CONTACT_UNLOCKED', 'CONVERSATION_CLOSED'] as const;
export const FLAG_TYPES = ['OFF_PLATFORM_PUSH', 'MONEY_REQUEST', 'HARASSMENT'] as const;
export const CHAT_ROLES = ['HOST', 'COMPANION'] as const;
export const CHAT_EVENT_TYPES = ['MESSAGE', 'READ', 'TYPING'] as const;
const TIERS = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'] as const satisfies readonly VerificationTier[];

export type MessageKind = (typeof MESSAGE_KINDS)[number];
export type SystemCode = (typeof SYSTEM_CODES)[number];
export type FlagType = (typeof FLAG_TYPES)[number];
export type ChatRole = (typeof CHAT_ROLES)[number];

/** The longest message the server keeps, in characters, once markup is stripped. */
export const MAX_MESSAGE_LENGTH = 2000;

/** ChatMessageResponse. No user ID: in a conversation of two, `mine` says who wrote it. */
export interface ChatMessage {
  id: string;
  seq: number;
  kind: MessageKind;
  mine: boolean;
  /** Plain text. Null for a SYSTEM message, and for one whose writer's data has been erased. */
  body: string | null;
  systemCode: SystemCode | null;
  redacted: boolean;
  flag: FlagType | null;
  sentAt: string;
}

/** ConversationSummaryResponse. */
export interface ConversationSummary {
  id: string;
  /** The accepted join request this chat belongs to; the meet-up screens are addressed by it. */
  matchId: string | null;
  trip: { id: string; title: string; destinationName: string; startDate: string; endDate: string; status: TripStatus };
  otherPerson: { firstName: string; verificationTier: VerificationTier };
  myRole: ChatRole;
  canSend: boolean;
  contactUnlocked: boolean;
  unreadCount: number;
  lastMessageAt: string | null;
  lastMessage: {
    seq: number;
    kind: MessageKind;
    mine: boolean;
    preview: string | null;
    systemCode: SystemCode | null;
    flag: FlagType | null;
  } | null;
  createdAt: string;
}

/** ConversationDetailResponse: the conversation screen in one call. */
export interface ConversationDetail {
  conversation: ConversationSummary;
  /** Oldest first. */
  messages: ChatMessage[];
  hasOlder: boolean;
}

export interface MessagePage {
  items: ChatMessage[];
  hasOlder: boolean;
}

/** ContactResponse, once the trip has locked. */
export interface Contact {
  fullName: string;
  email: string;
  phone: string | null;
}

/** ChatEventResponse, as the socket pushes it. */
export type ChatEvent =
  | { type: 'MESSAGE'; conversationId: string; message: ChatMessage; unreadCount: number }
  | { type: 'READ'; conversationId: string; unreadCount: number }
  | { type: 'TYPING'; conversationId: string };

export function createChatApi(client: AxiosInstance) {
  const base = (conversationId: string) => `/conversations/${encodeURIComponent(conversationId)}`;
  return {
    async inbox(page: number): Promise<Page<ConversationSummary>> {
      const response = await client.get<unknown>('/conversations', { params: { page } });
      return readPage(response.data, readSummary);
    },

    async conversation(conversationId: string): Promise<ConversationDetail> {
      const response = await client.get<unknown>(base(conversationId));
      return readDetail(response.data);
    },

    async olderMessages(conversationId: string, beforeSeq: number): Promise<MessagePage> {
      const response = await client.get<unknown>(`${base(conversationId)}/messages`, {
        params: { before: beforeSeq },
      });
      return readMessagePage(response.data);
    },

    async send(conversationId: string, clientMessageId: string, body: string): Promise<ChatMessage> {
      const response = await client.post<unknown>(`${base(conversationId)}/messages`, { clientMessageId, body });
      return readMessage(response.data);
    },

    async markRead(conversationId: string, upToSeq: number): Promise<void> {
      await client.post(`${base(conversationId)}/read`, { upToSeq });
    },

    async icebreakers(conversationId: string): Promise<string[]> {
      const response = await client.get<unknown>(`${base(conversationId)}/icebreakers`);
      const data = response.data;
      if (isRecord(data) && Array.isArray(data['suggestions']) && data['suggestions'].every(isNonEmptyString)) {
        return data['suggestions'];
      }
      throw new Error('Unexpected icebreakers.');
    },

    async contact(conversationId: string): Promise<Contact> {
      const response = await client.get<unknown>(`${base(conversationId)}/contact`);
      const data = response.data;
      if (
        isRecord(data) &&
        isNonEmptyString(data['fullName']) &&
        isNonEmptyString(data['email']) &&
        (data['phone'] === null || isNonEmptyString(data['phone']))
      ) {
        return { fullName: data['fullName'], email: data['email'], phone: data['phone'] };
      }
      throw new Error('Unexpected contact.');
    },
  };
}

export type ChatApi = ReturnType<typeof createChatApi>;

export const chatApi: ChatApi = createChatApi(apiClient);

/**
 * A pushed event, or null for anything that is not one: a socket frame is
 * as untrusted as any response, and a malformed one is dropped, not shown.
 */
export function readChatEvent(raw: unknown): ChatEvent | null {
  if (!isRecord(raw) || !isNonEmptyString(raw['conversationId'])) {
    return null;
  }
  const conversationId = raw['conversationId'];
  try {
    switch (oneOf(CHAT_EVENT_TYPES, raw['type'])) {
      case 'MESSAGE':
        if (isCount(raw['unreadCount'])) {
          return { type: 'MESSAGE', conversationId, message: readMessage(raw['message']), unreadCount: raw['unreadCount'] };
        }
        return null;
      case 'READ':
        return isCount(raw['unreadCount']) ? { type: 'READ', conversationId, unreadCount: raw['unreadCount'] } : null;
      case 'TYPING':
        return { type: 'TYPING', conversationId };
      case null:
        return null;
    }
  } catch {
    return null;
  }
}

/**
 * A message is accepted only in a shape the server can produce: USER with
 * text-or-null and no code, SYSTEM with a code and no text, flags only on
 * USER messages.
 */
function readMessage(raw: unknown): ChatMessage {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isPositive(raw['seq']) &&
    typeof raw['mine'] === 'boolean' &&
    typeof raw['redacted'] === 'boolean' &&
    isNonEmptyString(raw['sentAt']) &&
    (raw['body'] === null || typeof raw['body'] === 'string')
  ) {
    const kind = oneOf(MESSAGE_KINDS, raw['kind']);
    const systemCode = raw['systemCode'] === null ? null : oneOf(SYSTEM_CODES, raw['systemCode']);
    const flag = raw['flag'] === null ? null : oneOf(FLAG_TYPES, raw['flag']);
    const codeFits = kind === 'SYSTEM' ? systemCode !== null && raw['body'] === null : raw['systemCode'] === null;
    const flagFits = raw['flag'] === null || (kind === 'USER' && flag !== null);
    if (kind !== null && codeFits && flagFits) {
      return {
        id: raw['id'],
        seq: raw['seq'],
        kind,
        mine: raw['mine'],
        body: raw['body'],
        systemCode,
        redacted: raw['redacted'],
        flag,
        sentAt: raw['sentAt'],
      };
    }
  }
  throw new Error('Unexpected message.');
}

function readSummary(raw: unknown): ConversationSummary {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    (raw['matchId'] === null || isNonEmptyString(raw['matchId'])) &&
    isRecord(raw['trip']) &&
    isRecord(raw['otherPerson']) &&
    typeof raw['canSend'] === 'boolean' &&
    typeof raw['contactUnlocked'] === 'boolean' &&
    isCount(raw['unreadCount']) &&
    (raw['lastMessageAt'] === null || isNonEmptyString(raw['lastMessageAt'])) &&
    isNonEmptyString(raw['createdAt'])
  ) {
    const trip = raw['trip'];
    const other = raw['otherPerson'];
    const tripStatus = oneOf(TRIP_STATUSES, trip['status']);
    const tier = oneOf(TIERS, other['verificationTier']);
    const myRole = oneOf(CHAT_ROLES, raw['myRole']);
    const lastMessage = readLastMessage(raw['lastMessage']);
    if (
      tripStatus !== null &&
      tier !== null &&
      myRole !== null &&
      lastMessage !== undefined &&
      isNonEmptyString(trip['id']) &&
      isNonEmptyString(trip['title']) &&
      isNonEmptyString(trip['destinationName']) &&
      isDate(trip['startDate']) &&
      isDate(trip['endDate']) &&
      isNonEmptyString(other['firstName'])
    ) {
      return {
        id: raw['id'],
        matchId: raw['matchId'],
        trip: {
          id: trip['id'],
          title: trip['title'],
          destinationName: trip['destinationName'],
          startDate: trip['startDate'],
          endDate: trip['endDate'],
          status: tripStatus,
        },
        otherPerson: { firstName: other['firstName'], verificationTier: tier },
        myRole,
        canSend: raw['canSend'],
        contactUnlocked: raw['contactUnlocked'],
        unreadCount: raw['unreadCount'],
        lastMessageAt: raw['lastMessageAt'],
        lastMessage,
        createdAt: raw['createdAt'],
      };
    }
  }
  throw new Error('Unexpected conversation.');
}

/** undefined for a malformed value, so the caller can tell it from a real null. */
function readLastMessage(raw: unknown): ConversationSummary['lastMessage'] | undefined {
  if (raw === null) {
    return null;
  }
  if (
    isRecord(raw) &&
    isPositive(raw['seq']) &&
    typeof raw['mine'] === 'boolean' &&
    (raw['preview'] === null || typeof raw['preview'] === 'string')
  ) {
    const kind = oneOf(MESSAGE_KINDS, raw['kind']);
    const systemCode = raw['systemCode'] === null ? null : oneOf(SYSTEM_CODES, raw['systemCode']);
    const flag = raw['flag'] === null ? null : oneOf(FLAG_TYPES, raw['flag']);
    if (kind !== null && (raw['systemCode'] === null || systemCode !== null) && (raw['flag'] === null || flag !== null)) {
      return { seq: raw['seq'], kind, mine: raw['mine'], preview: raw['preview'], systemCode, flag };
    }
  }
  return undefined;
}

function readDetail(raw: unknown): ConversationDetail {
  if (isRecord(raw) && Array.isArray(raw['messages']) && typeof raw['hasOlder'] === 'boolean') {
    return {
      conversation: readSummary(raw['conversation']),
      messages: readOrdered(raw['messages']),
      hasOlder: raw['hasOlder'],
    };
  }
  throw new Error('Unexpected conversation detail.');
}

function readMessagePage(raw: unknown): MessagePage {
  if (isRecord(raw) && Array.isArray(raw['items']) && typeof raw['hasOlder'] === 'boolean') {
    return { items: readOrdered(raw['items']), hasOlder: raw['hasOlder'] };
  }
  throw new Error('Unexpected message page.');
}

/** Messages must arrive oldest first with rising numbers, as the server sends them. */
function readOrdered(raw: unknown[]): ChatMessage[] {
  const messages = raw.map(readMessage);
  for (let i = 1; i < messages.length; i++) {
    if ((messages[i]?.seq ?? 0) <= (messages[i - 1]?.seq ?? 0)) {
      throw new Error('Messages out of order.');
    }
  }
  return messages;
}

function readPage<T>(body: unknown, readItem: (raw: unknown) => T): Page<T> {
  if (
    isRecord(body) &&
    Array.isArray(body['items']) &&
    isCount(body['page']) &&
    isCount(body['size']) &&
    isCount(body['totalItems']) &&
    isCount(body['totalPages'])
  ) {
    return {
      items: body['items'].map(readItem),
      page: body['page'],
      size: body['size'],
      totalItems: body['totalItems'],
      totalPages: body['totalPages'],
    };
  }
  throw new Error('Unexpected page.');
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function isPositive(value: unknown): value is number {
  return isCount(value) && value > 0;
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
