import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { chatApi, type ChatEvent, type ConversationSummary } from '../api/chatApi';
import type { Page } from '../api/verificationApi';
import { CONNECTION_NOTICES, formatSentAt, SYSTEM_MESSAGE_LABELS } from '../chat/chatLabels';
import { onChatEvent } from '../chat/chatEvents';
import { ErrorNotice } from '../components/ErrorNotice';
import { Pager } from '../components/Pager';
import { useApiResource } from '../hooks/useApiResource';
import { useChatStore } from '../stores/chatStore';
import { formatTripDates, PERSON_TIER_LABELS } from '../trips/tripLabels';

/**
 * The caller's conversations, most recent first, each with its unread
 * count and the start of its last message. New messages move a
 * conversation to the top as they arrive; one the list has not seen yet
 * reloads the page.
 */
export function ConversationsPage() {
  const [page, setPage] = useState(0);
  const load = useCallback(() => chatApi.inbox(page), [page]);
  const { state, reload } = useApiResource(load);
  const loaded = state.kind === 'ready' ? state.data : null;
  // Live changes are kept against the page they were applied to, so a
  // reload starts again from the server's answer.
  const [live, setLive] = useState<{ base: Page<ConversationSummary>; page: Page<ConversationSummary> } | null>(null);
  const shown = live !== null && live.base === loaded ? live.page : loaded;
  const status = useChatStore((store) => store.status);
  const notice = CONNECTION_NOTICES[status];

  useEffect(() => {
    if (loaded === null) {
      return;
    }
    const setUnread = useChatStore.getState().setUnread;
    for (const conversation of loaded.items) {
      setUnread(conversation.id, conversation.unreadCount);
    }
  }, [loaded]);

  useEffect(() => {
    if (loaded === null) {
      return;
    }
    return onChatEvent((event) => {
      if (event.type === 'TYPING') {
        return;
      }
      // Live updates only reorder and refresh rows; a conversation this page
      // has never shown arrives with a reload.
      if (!loaded.items.some((item) => item.id === event.conversationId)) {
        if (event.type === 'MESSAGE') {
          reload();
        }
        return;
      }
      setLive((current) => ({
        base: loaded,
        page: applyEvent(current !== null && current.base === loaded ? current.page : loaded, event),
      }));
    });
  }, [loaded, reload]);

  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-bold text-teal-800">Messages</h1>
      <p className="mt-2 text-sm text-slate-600">
        Chats with your hosts and companions. Contact details stay hidden until your trip is locked.
      </p>
      {notice !== null && (
        <p role="status" className="mt-3 text-sm text-slate-500">
          {notice}
        </p>
      )}
      <div className="mt-6">
        {state.kind === 'loading' && (
          <p role="status" className="text-slate-600">
            Loading your conversations…
          </p>
        )}
        {state.kind === 'failed' && (
          <div className="space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        )}
        {shown !== null && shown.items.length === 0 && (
          <div className="rounded-md border border-slate-200 bg-white px-4 py-6 text-sm text-slate-700">
            <p>No conversations yet.</p>
            <p className="mt-1">
              A chat opens when a host accepts your request to join, or when you accept someone on your trip.
            </p>
            <Link to="/trips" className="mt-3 inline-block font-medium text-teal-700 underline">
              Find a trip
            </Link>
          </div>
        )}
        {shown !== null && shown.items.length > 0 && (
          <>
            <ul className="divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
              {shown.items.map((conversation) => (
                <li key={conversation.id}>
                  <ConversationRow conversation={conversation} />
                </li>
              ))}
            </ul>
            <Pager label="Conversation pages" page={shown.page} totalPages={shown.totalPages} onChange={setPage} />
          </>
        )}
      </div>
    </section>
  );
}

function ConversationRow({ conversation }: { conversation: ConversationSummary }) {
  const { otherPerson, trip, unreadCount } = conversation;
  return (
    <Link
      to={`/messages/${conversation.id}`}
      className="flex items-start gap-4 px-4 py-3 hover:bg-slate-50"
      aria-label={`Conversation with ${otherPerson.firstName} about ${trip.title}${
        unreadCount > 0 ? `, ${String(unreadCount)} unread` : ''
      }`}
    >
      <div className="min-w-0 flex-1">
        <p className="font-medium text-slate-900">
          {otherPerson.firstName}{' '}
          <span className="text-xs font-normal text-slate-500">({PERSON_TIER_LABELS[otherPerson.verificationTier]})</span>
        </p>
        <p className="text-xs text-slate-500">
          {trip.title} · {formatTripDates(trip.startDate, trip.endDate)}
          {conversation.myRole === 'HOST' ? ' · your trip' : ''}
        </p>
        <p className="mt-1 truncate text-sm text-slate-700">{lastMessageText(conversation)}</p>
      </div>
      <div className="flex shrink-0 flex-col items-end gap-1">
        {conversation.lastMessageAt !== null && (
          <span className="text-xs text-slate-500">{formatSentAt(conversation.lastMessageAt)}</span>
        )}
        {unreadCount > 0 && (
          <span className="rounded-full bg-teal-700 px-2 py-0.5 text-xs font-semibold text-white">{unreadCount}</span>
        )}
        {conversation.lastMessage?.flag != null && <span className="text-xs text-amber-700">Flagged</span>}
      </div>
    </Link>
  );
}

/** A new message moves its conversation to the top; a read only updates the count. */
function applyEvent(
  page: Page<ConversationSummary>,
  event: Exclude<ChatEvent, { type: 'TYPING' }>,
): Page<ConversationSummary> {
  const known = page.items.find((item) => item.id === event.conversationId);
  if (known === undefined) {
    return page;
  }
  if (event.type === 'READ') {
    return {
      ...page,
      items: page.items.map((item) => (item.id === known.id ? { ...item, unreadCount: event.unreadCount } : item)),
    };
  }
  const { message } = event;
  const updated: ConversationSummary = {
    ...known,
    unreadCount: event.unreadCount,
    lastMessageAt: message.sentAt,
    lastMessage: {
      seq: message.seq,
      kind: message.kind,
      mine: message.mine,
      preview: message.body,
      systemCode: message.systemCode,
      flag: message.flag,
    },
  };
  return { ...page, items: [updated, ...page.items.filter((item) => item.id !== known.id)] };
}

function lastMessageText(conversation: ConversationSummary): string {
  const last = conversation.lastMessage;
  if (last === null) {
    return 'No messages yet. Say hello.';
  }
  if (last.kind === 'SYSTEM') {
    return last.systemCode === null ? '' : SYSTEM_MESSAGE_LABELS[last.systemCode];
  }
  const text = last.preview ?? 'Message no longer available';
  return last.mine ? `You: ${text}` : text;
}
