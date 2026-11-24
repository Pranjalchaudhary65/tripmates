import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type SubmitEvent } from 'react';
import { Link, useParams } from 'react-router';
import { chatApi, type ChatMessage, type Contact, type ConversationDetail, type ConversationSummary } from '../api/chatApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { chatConnection } from '../chat/chatConnection';
import { onChatEvent } from '../chat/chatEvents';
import {
  CONNECTION_NOTICES,
  FLAG_WARNINGS,
  formatSentAt,
  OWN_FLAG_NOTES,
  SYSTEM_MESSAGE_LABELS,
} from '../chat/chatLabels';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { useChatStore } from '../stores/chatStore';
import { isUuid } from '../trips/ids';
import { formatTripDates, PERSON_TIER_LABELS } from '../trips/tripLabels';
import { validateMessage } from '../validation/chatRules';

/** How long "is typing" stays up after the last notice. */
const TYPING_SHOWN_MS = 4_000;

/**
 * One conversation: its history, live new messages, and the composer. An ID
 * that is not a UUID is never sent; it gets the same "not available"
 * screen as the server's 404, whether the conversation does not exist or
 * the caller is not in it.
 */
export function ConversationPage() {
  const { conversationId = '' } = useParams();
  if (!isUuid(conversationId)) {
    return <Unavailable />;
  }
  return <ConversationLoader key={conversationId} conversationId={conversationId} />;
}

function ConversationLoader({ conversationId }: { conversationId: string }) {
  const load = useCallback(() => chatApi.conversation(conversationId), [conversationId]);
  const { state, reload } = useApiResource(load);

  if (state.kind === 'failed' && state.failure.status === 404) {
    return <Unavailable />;
  }
  return (
    <section className="flex max-w-3xl flex-col">
      {state.kind === 'loading' && (
        <>
          <h1 className="text-2xl font-bold text-teal-800">Conversation</h1>
          <p role="status" className="mt-6 text-slate-600">
            Loading the conversation…
          </p>
        </>
      )}
      {state.kind === 'failed' && (
        <>
          <h1 className="text-2xl font-bold text-teal-800">Conversation</h1>
          <div className="mt-6 space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        </>
      )}
      {state.kind === 'ready' && <ConversationView initial={state.data} />}
    </section>
  );
}

interface Outgoing {
  clientMessageId: string;
  body: string;
  failure: ApiFailure | null;
}

function ConversationView({ initial }: { initial: ConversationDetail }) {
  const conversationId = initial.conversation.id;
  const [summary, setSummary] = useState<ConversationSummary>(initial.conversation);
  const [messages, setMessages] = useState<ChatMessage[]>(initial.messages);
  const [hasOlder, setHasOlder] = useState(initial.hasOlder);
  const [pending, setPending] = useState<Outgoing[]>([]);
  const [typing, setTyping] = useState(false);
  const messagesRef = useRef(messages);
  const markedRef = useRef(0);
  const status = useChatStore((store) => store.status);
  const lastStatus = useRef(status);
  const notice = CONNECTION_NOTICES[status];

  const merge = useCallback((incoming: readonly ChatMessage[]) => {
    setMessages((current) => {
      const bySeq = new Map(current.map((message) => [message.seq, message]));
      for (const message of incoming) {
        bySeq.set(message.seq, message);
      }
      return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
    });
  }, []);

  /** Catches up after a missed push: a gap in the numbers, or a reconnect. */
  const refresh = useCallback(() => {
    chatApi.conversation(conversationId).then(
      (fresh) => {
        setSummary(fresh.conversation);
        merge(fresh.messages);
      },
      () => {
        // The next push or reconnect tries again; the messages shown stay.
      },
    );
  }, [conversationId, merge]);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  useEffect(() => {
    let typingTimer: ReturnType<typeof setTimeout> | undefined;
    const unsubscribe = onChatEvent((event) => {
      if (event.conversationId !== conversationId) {
        return;
      }
      if (event.type === 'TYPING') {
        setTyping(true);
        clearTimeout(typingTimer);
        typingTimer = setTimeout(() => {
          setTyping(false);
        }, TYPING_SHOWN_MS);
      } else if (event.type === 'MESSAGE') {
        const newest = messagesRef.current.at(-1)?.seq ?? 0;
        if (!event.message.mine) {
          setTyping(false);
        }
        if (event.message.seq > newest + 1) {
          refresh();
        } else {
          merge([event.message]);
        }
      }
    });
    return () => {
      unsubscribe();
      clearTimeout(typingTimer);
    };
  }, [conversationId, merge, refresh]);

  useEffect(() => {
    if (status === 'online' && lastStatus.current !== 'online') {
      refresh();
    }
    lastStatus.current = status;
  }, [status, refresh]);

  // Marks everything shown as read while the tab is visible.
  useEffect(() => {
    function markRead() {
      const newest = messagesRef.current.at(-1)?.seq ?? 0;
      if (newest > markedRef.current && document.visibilityState === 'visible') {
        markedRef.current = newest;
        useChatStore.getState().setUnread(conversationId, 0);
        chatApi.markRead(conversationId, newest).catch(() => {
          markedRef.current = 0;
        });
      }
    }
    markRead();
    document.addEventListener('visibilitychange', markRead);
    return () => {
      document.removeEventListener('visibilitychange', markRead);
    };
  }, [conversationId, messages]);

  const send = useCallback(
    (body: string, clientMessageId: string) => {
      setPending((current) => [
        ...current.filter((item) => item.clientMessageId !== clientMessageId),
        { clientMessageId, body, failure: null },
      ]);
      chatApi.send(conversationId, clientMessageId, body).then(
        (message) => {
          setPending((current) => current.filter((item) => item.clientMessageId !== clientMessageId));
          merge([message]);
        },
        (error: unknown) => {
          const failure = toApiFailure(error);
          setPending((current) =>
            current.map((item) => (item.clientMessageId === clientMessageId ? { ...item, failure } : item)),
          );
          if (failure.code === 'conversation_closed') {
            setSummary((current) => ({ ...current, canSend: false }));
          }
        },
      );
    },
    [conversationId, merge],
  );

  const hasUserMessages = messages.some((message) => message.kind === 'USER') || pending.length > 0;
  const other = summary.otherPerson;

  return (
    <>
      <header>
        <h1 className="text-2xl font-bold text-teal-800">{other.firstName}</h1>
        <p className="mt-1 text-sm text-slate-600">
          {PERSON_TIER_LABELS[other.verificationTier]} ·{' '}
          <Link to={`/trips/${summary.trip.id}`} className="text-teal-700 underline">
            {summary.trip.title}
          </Link>{' '}
          · {formatTripDates(summary.trip.startDate, summary.trip.endDate)}
        </p>
        {summary.matchId !== null && isUuid(summary.matchId) && summary.canSend && (
          <Link to={`/matches/${summary.matchId}`} className="mt-2 inline-block text-sm font-medium text-teal-700 underline">
            Video call and public meet-up
          </Link>
        )}
        {notice !== null && (
          <p role="status" className="mt-2 text-xs text-slate-500">
            {notice}
          </p>
        )}
      </header>

      <ContactPanel conversationId={conversationId} unlocked={summary.contactUnlocked} firstName={other.firstName} />

      <MessageList
        conversationId={conversationId}
        messages={messages}
        hasOlder={hasOlder}
        onOlder={(older) => {
          merge(older.items);
          setHasOlder(older.hasOlder);
        }}
        pending={pending}
        onRetry={(item) => {
          send(item.body, item.clientMessageId);
        }}
        otherFirstName={other.firstName}
      />

      {typing && (
        <p role="status" className="mt-2 text-xs text-slate-500">
          {other.firstName} is typing…
        </p>
      )}

      {summary.canSend ? (
        <Composer
          conversationId={conversationId}
          showIcebreakers={!hasUserMessages}
          onSend={(body) => {
            send(body, crypto.randomUUID());
          }}
        />
      ) : (
        <p className="mt-6 rounded-md border border-slate-200 bg-slate-100 px-4 py-3 text-sm text-slate-700">
          This conversation is closed. You can still read it.
        </p>
      )}
    </>
  );
}

function MessageList({
  conversationId,
  messages,
  hasOlder,
  onOlder,
  pending,
  onRetry,
  otherFirstName,
}: {
  conversationId: string;
  messages: readonly ChatMessage[];
  hasOlder: boolean;
  onOlder: (page: { items: ChatMessage[]; hasOlder: boolean }) => void;
  pending: readonly Outgoing[];
  onRetry: (item: Outgoing) => void;
  otherFirstName: string;
}) {
  const listRef = useRef<HTMLOListElement>(null);
  const [olderFailure, setOlderFailure] = useState<ApiFailure | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const newestSeq = messages.at(-1)?.seq ?? 0;

  useEffect(() => {
    const list = listRef.current;
    if (list !== null) {
      list.scrollTop = list.scrollHeight;
    }
  }, [newestSeq, pending.length]);

  function loadOlder() {
    const first = messages[0];
    if (first === undefined) {
      return;
    }
    setLoadingOlder(true);
    setOlderFailure(null);
    chatApi
      .olderMessages(conversationId, first.seq)
      .then(onOlder, (error: unknown) => {
        setOlderFailure(toApiFailure(error));
      })
      .finally(() => {
        setLoadingOlder(false);
      });
  }

  return (
    <div className="mt-6">
      {hasOlder && (
        <div className="mb-3 space-y-2 text-center">
          <button
            type="button"
            onClick={loadOlder}
            disabled={loadingOlder}
            className="text-sm font-medium text-teal-700 underline disabled:text-slate-400"
          >
            {loadingOlder ? 'Loading…' : 'Show earlier messages'}
          </button>
          {olderFailure !== null && (
            <ErrorNotice message={olderFailure.message} correlationId={olderFailure.correlationId} />
          )}
        </div>
      )}
      {messages.length === 0 && pending.length === 0 ? (
        <p className="rounded-md border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-600">
          No messages yet. Say hello to {otherFirstName}.
        </p>
      ) : (
        <ol
          ref={listRef}
          aria-label="Messages"
          className="max-h-[60vh] space-y-3 overflow-y-auto rounded-md border border-slate-200 bg-white p-4"
        >
          {messages.map((message) => (
            <li key={message.id}>
              <Bubble message={message} />
            </li>
          ))}
          {pending.map((item) => (
            <li key={item.clientMessageId}>
              <PendingBubble item={item} onRetry={onRetry} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function Bubble({ message }: { message: ChatMessage }) {
  if (message.kind === 'SYSTEM') {
    return (
      <p className="text-center text-xs text-slate-500">
        {message.systemCode === null ? '' : SYSTEM_MESSAGE_LABELS[message.systemCode]}
      </p>
    );
  }
  const side = message.mine ? 'ml-auto bg-teal-700 text-white' : 'mr-auto bg-slate-100 text-slate-900';
  return (
    <div className={`flex flex-col ${message.mine ? 'items-end' : 'items-start'}`}>
      <div className={`max-w-[80%] rounded-lg px-3 py-2 ${side}`}>
        {message.body === null ? (
          <p className="italic opacity-80">This message is no longer available.</p>
        ) : (
          <p className="whitespace-pre-wrap break-words">{message.body}</p>
        )}
      </div>
      <p className="mt-1 text-xs text-slate-500">
        {formatSentAt(message.sentAt)}
        {message.redacted && ' · Contact details were hidden'}
      </p>
      {message.flag !== null && (
        <p role="note" className="mt-1 max-w-[80%] rounded bg-amber-50 px-2 py-1 text-xs text-amber-900">
          {message.mine ? OWN_FLAG_NOTES[message.flag] : FLAG_WARNINGS[message.flag]}
        </p>
      )}
    </div>
  );
}

function PendingBubble({ item, onRetry }: { item: Outgoing; onRetry: (item: Outgoing) => void }) {
  return (
    <div className="flex flex-col items-end">
      <div className="ml-auto max-w-[80%] rounded-lg bg-teal-700/70 px-3 py-2 text-white">
        <p className="whitespace-pre-wrap break-words">{item.body}</p>
      </div>
      {item.failure === null ? (
        <p className="mt-1 text-xs text-slate-500">Sending…</p>
      ) : (
        <div className="mt-1 w-full max-w-[80%] space-y-1">
          <ErrorNotice message={item.failure.message} correlationId={item.failure.correlationId} />
          {item.failure.code !== 'conversation_closed' && (
            <button
              type="button"
              onClick={() => {
                onRetry(item);
              }}
              className="text-xs font-medium text-teal-700 underline"
            >
              Try sending again
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function Composer({
  conversationId,
  showIcebreakers,
  onSend,
}: {
  conversationId: string;
  showIcebreakers: boolean;
  onSend: (body: string) => void;
}) {
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  function submit(event?: SubmitEvent<HTMLFormElement>) {
    event?.preventDefault();
    const problem = validateMessage(draft);
    if (problem !== null) {
      setError(problem);
      return;
    }
    setError(null);
    onSend(draft.trim());
    setDraft('');
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submit();
    }
  }

  return (
    <form onSubmit={submit} className="mt-4 space-y-2" noValidate>
      {showIcebreakers && (
        <Icebreakers
          conversationId={conversationId}
          onPick={(line) => {
            setDraft(line);
            setError(null);
          }}
        />
      )}
      <label htmlFor="chat-message" className="block text-sm font-medium text-slate-700">
        Message
      </label>
      <textarea
        id="chat-message"
        value={draft}
        rows={3}
        onChange={(event) => {
          setDraft(event.target.value);
          setError(null);
          chatConnection.sendTyping(conversationId);
        }}
        onKeyDown={onKeyDown}
        aria-invalid={error !== null}
        aria-describedby={error !== null ? 'chat-message-error' : 'chat-message-hint'}
        className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-teal-600 focus:outline-none"
      />
      {error !== null ? (
        <p id="chat-message-error" className="text-sm text-red-700">
          {error}
        </p>
      ) : (
        <p id="chat-message-hint" className="text-xs text-slate-500">
          Enter sends, Shift+Enter starts a new line. Phone numbers, emails and links are hidden until your trip is
          locked.
        </p>
      )}
      <button
        type="submit"
        className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800"
      >
        Send
      </button>
    </form>
  );
}

/** Opening lines to start from. Picking one only fills the box; nothing is sent until the user sends it. */
function Icebreakers({ conversationId, onPick }: { conversationId: string; onPick: (line: string) => void }) {
  const load = useCallback(() => chatApi.icebreakers(conversationId), [conversationId]);
  const { state } = useApiResource(load);
  if (state.kind !== 'ready' || state.data.length === 0) {
    return null;
  }
  return (
    <div>
      <p className="text-sm font-medium text-slate-700">Not sure how to start?</p>
      <ul aria-label="Suggestions" className="mt-2 flex flex-wrap gap-2">
        {state.data.map((line) => (
          <li key={line}>
            <button
              type="button"
              onClick={() => {
                onPick(line);
              }}
              className="rounded-full border border-teal-600 px-3 py-1 text-left text-sm text-teal-800 hover:bg-teal-50"
            >
              {line}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ContactPanel({
  conversationId,
  unlocked,
  firstName,
}: {
  conversationId: string;
  unlocked: boolean;
  firstName: string;
}) {
  const [contact, setContact] = useState<Contact | null>(null);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(false);

  if (!unlocked) {
    return (
      <p className="mt-4 rounded-md bg-slate-100 px-4 py-3 text-sm text-slate-700">
        Contact details stay hidden until your trip is locked, after you have met in person at a public place. Keep
        the conversation here until then.
      </p>
    );
  }

  function reveal() {
    setLoading(true);
    setFailure(null);
    chatApi
      .contact(conversationId)
      .then(setContact, (error: unknown) => {
        setFailure(toApiFailure(error));
      })
      .finally(() => {
        setLoading(false);
      });
  }

  return (
    <div role="region" aria-label="Contact details" className="mt-4 rounded-md border border-teal-200 bg-teal-50 px-4 py-3 text-sm">
      <p className="font-medium text-teal-900">Your trip is locked. You can now see {firstName}&apos;s contact details.</p>
      {contact === null ? (
        <button
          type="button"
          onClick={reveal}
          disabled={loading}
          className="mt-2 font-medium text-teal-700 underline disabled:text-slate-400"
        >
          {loading ? 'Loading…' : 'Show contact details'}
        </button>
      ) : (
        <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
          <dt className="text-slate-600">Name</dt>
          <dd>{contact.fullName}</dd>
          <dt className="text-slate-600">Email</dt>
          <dd>{contact.email}</dd>
          <dt className="text-slate-600">Phone</dt>
          <dd>{contact.phone ?? 'No verified phone number'}</dd>
        </dl>
      )}
      {failure !== null && (
        <div className="mt-2">
          <ErrorNotice message={failure.message} correlationId={failure.correlationId} />
        </div>
      )}
    </div>
  );
}

function Unavailable() {
  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-bold text-teal-800">Conversation not available</h1>
      <p className="mt-4 text-slate-700">This conversation does not exist, or you are not part of it.</p>
      <Link to="/messages" className="mt-3 inline-block font-medium text-teal-700 underline">
        Back to your messages
      </Link>
    </section>
  );
}
