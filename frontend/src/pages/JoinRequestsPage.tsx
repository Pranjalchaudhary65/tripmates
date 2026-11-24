import { useCallback, useState, type ChangeEvent, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { tripsApi, type JoinDecision, type JoinRequest } from '../api/tripsApi';
import type { Page } from '../api/verificationApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { inputClassName } from '../components/FormField';
import { Pager } from '../components/Pager';
import { useApiResource, type ResourceState } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';
import { isUuid } from '../trips/ids';
import {
  formatTripDates,
  INCOMING_STATUS_LABELS,
  PERSON_TIER_LABELS,
  placesLeft,
  REQUEST_STATUS_LABELS,
} from '../trips/tripLabels';

const WHEN = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

/** The host's status filter; "ALL" sends none. */
const INCOMING_FILTERS = ['REQUESTED', 'ACCEPTED', 'REJECTED', 'ALL'] as const;
type IncomingFilter = (typeof INCOMING_FILTERS)[number];
const INCOMING_FILTER_LABELS: Record<IncomingFilter, string> = {
  REQUESTED: 'Waiting for an answer',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
  ALL: 'All requests',
};

/**
 * Both sides of join requests. "On my trips" is the host's inbox, with
 * accept and reject; "Sent by me" is where the caller's own requests stand.
 * Which view, status and trip are shown lives in the URL. A trip filter that
 * is not a UUID is dropped, and one for somebody else's trip only gives an
 * empty list: the server narrows the caller's own inbox and nothing more.
 */
export function JoinRequestsPage() {
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'sent' ? 'sent' : 'incoming';
  const tripParam = params.get('trip') ?? '';
  const tripId = isUuid(tripParam) ? tripParam : null;

  function show(next: 'incoming' | 'sent') {
    setParams(next === 'sent' ? { view: 'sent' } : {});
  }

  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-bold text-teal-800">Join requests</h1>
      <div role="tablist" aria-label="Which requests" className="mt-4 flex gap-2 border-b border-slate-200">
        <Tab
          selected={view === 'incoming'}
          label="On my trips"
          onSelect={() => {
            show('incoming');
          }}
        />
        <Tab
          selected={view === 'sent'}
          label="Sent by me"
          onSelect={() => {
            show('sent');
          }}
        />
      </div>
      {view === 'incoming' ? <Incoming tripId={tripId} /> : <Sent />}
    </section>
  );
}

function Tab({ selected, label, onSelect }: { selected: boolean; label: string; onSelect: () => void }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      onClick={onSelect}
      className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
        selected ? 'border-teal-700 text-teal-800' : 'border-transparent text-slate-600 hover:text-teal-700'
      }`}
    >
      {label}
    </button>
  );
}

function Incoming({ tripId }: { tripId: string | null }) {
  const [filter, setFilter] = useState<IncomingFilter>('REQUESTED');
  const [page, setPage] = useState(0);
  const load = useCallback(
    () => tripsApi.incoming({ status: filter === 'ALL' ? null : filter, tripId, page }),
    [filter, tripId, page],
  );
  const { state, reload } = useApiResource(load);
  const [decisions, setDecisions] = useState<Record<string, JoinDecision>>({});

  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-end gap-4">
        <label className="text-sm font-medium text-slate-800">
          Show
          <select
            value={filter}
            onChange={(event: ChangeEvent<HTMLSelectElement>) => {
              const chosen = INCOMING_FILTERS.find((value) => value === event.target.value);
              if (chosen !== undefined) {
                setFilter(chosen);
                setPage(0);
              }
            }}
            className={inputClassName}
          >
            {INCOMING_FILTERS.map((value) => (
              <option key={value} value={value}>
                {INCOMING_FILTER_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
        {tripId !== null && (
          <p className="text-sm text-slate-600">
            One trip only.{' '}
            <Link to="/trips/requests" className="font-medium text-teal-700 underline">
              Show every trip
            </Link>
          </p>
        )}
      </div>
      <Listing
        state={state}
        reload={reload}
        loadingText="Loading requests on your trips…"
        emptyText={
          filter === 'REQUESTED' ? 'No requests are waiting for your answer.' : 'No requests match this filter.'
        }
        onPage={setPage}
        renderItem={(request) => (
          <IncomingRow
            key={request.id}
            request={request}
            decision={decisions[request.id] ?? null}
            onDecided={(decision) => {
              setDecisions((current) => ({ ...current, [request.id]: decision }));
            }}
          />
        )}
      />
    </div>
  );
}

function IncomingRow({
  request,
  decision,
  onDecided,
}: {
  request: JoinRequest;
  decision: JoinDecision | null;
  onDecided: (decision: JoinDecision) => void;
}) {
  const { pending, run } = useSubmission();
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const status = decision?.status ?? request.status;
  const slots = decision?.trip ?? request.trip;

  function decide(accept: boolean) {
    setFailure(null);
    void run(async () => {
      try {
        onDecided(await (accept ? tripsApi.accept(request.id) : tripsApi.reject(request.id)));
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  const name = `${request.person.firstName} (${PERSON_TIER_LABELS[request.person.verificationTier]})`;
  return (
    <li className="rounded-md border border-slate-200 bg-white px-4 py-3">
      <p className="font-medium text-slate-900">{name}</p>
      <p className="mt-1 text-sm text-slate-700">
        wants to join{' '}
        <Link to={`/trips/${request.trip.id}`} className="font-medium text-teal-700 underline">
          {request.trip.title}
        </Link>{' '}
        · {formatTripDates(request.trip.startDate, request.trip.endDate)} ·{' '}
        {placesLeft(slots.totalSlots, slots.filledSlots)}
      </p>
      <p className="mt-1 text-xs text-slate-500">Asked {WHEN.format(new Date(request.requestedAt))}</p>
      <p className="mt-2 text-sm">
        <span className="font-medium">{INCOMING_STATUS_LABELS[status]}</span>
      </p>
      {status === 'REQUESTED' && (
        <div className="mt-3 flex gap-3">
          <button
            type="button"
            disabled={pending}
            aria-busy={pending}
            onClick={() => {
              decide(true);
            }}
            className="rounded-md bg-teal-700 px-3 py-1.5 text-sm font-semibold text-white hover:bg-teal-800 disabled:bg-slate-400"
          >
            Accept {request.person.firstName}
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() => {
              decide(false);
            }}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-sm font-semibold text-slate-800 hover:bg-slate-100 disabled:text-slate-400"
          >
            Reject {request.person.firstName}
          </button>
        </div>
      )}
      {failure !== null && (
        <div className="mt-3">
          <ErrorNotice
            message={failure.message}
            correlationId={failure.correlationId}
            retrySecondsLeft={failure.retryAfterSeconds}
          />
        </div>
      )}
    </li>
  );
}

function Sent() {
  const [page, setPage] = useState(0);
  const load = useCallback(() => tripsApi.outgoing({ status: null, page }), [page]);
  const { state, reload } = useApiResource(load);

  return (
    <div className="mt-4">
      <Listing
        state={state}
        reload={reload}
        loadingText="Loading your requests…"
        emptyText="You have not asked to join any trips yet."
        emptyLink
        onPage={setPage}
        renderItem={(request) => (
          <li key={request.id} className="rounded-md border border-slate-200 bg-white px-4 py-3">
            <Link to={`/trips/${request.trip.id}`} className="font-medium text-teal-700 underline">
              {request.trip.title}
            </Link>
            <p className="mt-1 text-sm text-slate-700">
              {request.trip.destinationName} · {formatTripDates(request.trip.startDate, request.trip.endDate)} · hosted
              by {request.person.firstName}
            </p>
            <p className="mt-2 text-sm">
              <span className="font-medium">{REQUEST_STATUS_LABELS[request.status]}</span>
              <span className="text-slate-500"> · asked {WHEN.format(new Date(request.requestedAt))}</span>
            </p>
          </li>
        )}
      />
    </div>
  );
}

function Listing({
  state,
  reload,
  loadingText,
  emptyText,
  emptyLink = false,
  onPage,
  renderItem,
}: {
  state: ResourceState<Page<JoinRequest>>;
  reload: () => void;
  loadingText: string;
  emptyText: string;
  emptyLink?: boolean;
  onPage: (page: number) => void;
  renderItem: (request: JoinRequest) => ReactNode;
}) {
  if (state.kind === 'loading') {
    return (
      <p role="status" className="mt-6 text-slate-600">
        {loadingText}
      </p>
    );
  }
  if (state.kind === 'failed') {
    return (
      <div className="mt-6 space-y-3">
        <ErrorNotice
          message={state.failure.message}
          correlationId={state.failure.correlationId}
          retrySecondsLeft={state.failure.retryAfterSeconds}
        />
        <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
          Try again
        </button>
      </div>
    );
  }
  if (state.data.items.length === 0) {
    return (
      <p className="mt-6 text-slate-600">
        {emptyText}
        {emptyLink && (
          <>
            {' '}
            <Link to="/trips" className="font-medium text-teal-700 underline">
              Find a trip
            </Link>
          </>
        )}
      </p>
    );
  }
  return (
    <>
      <ul className="mt-6 space-y-3">{state.data.items.map(renderItem)}</ul>
      <Pager label="Request pages" page={state.data.page} totalPages={state.data.totalPages} onChange={onPage} />
    </>
  );
}
