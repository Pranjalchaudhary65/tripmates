import { useCallback, useState } from 'react';
import { Link } from 'react-router';
import { tripsApi } from '../api/tripsApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { Pager } from '../components/Pager';
import { TripCard } from '../components/TripCard';
import { useApiResource } from '../hooks/useApiResource';

/** The trips the caller hosts, upcoming first, each with its waiting requests. */
export function HostedTripsPage() {
  const [page, setPage] = useState(0);
  const load = useCallback(() => tripsApi.hosted(page), [page]);
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-3xl">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <h1 className="text-2xl font-bold text-teal-800">My trips</h1>
        <p className="flex gap-4 text-sm">
          <Link to="/trips/new" className="font-medium text-teal-700 underline">
            Host a trip
          </Link>
          <Link to="/trips/requests" className="font-medium text-teal-700 underline">
            Join requests
          </Link>
        </p>
      </div>
      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading your trips…
        </p>
      )}
      {state.kind === 'failed' && (
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
      )}
      {state.kind === 'ready' && state.data.items.length === 0 && (
        <p className="mt-6 text-slate-600">
          You are not hosting any trips yet.{' '}
          <Link to="/trips/new" className="font-medium text-teal-700 underline">
            Host your first one
          </Link>
          .
        </p>
      )}
      {state.kind === 'ready' && state.data.items.length > 0 && (
        <>
          <ul className="mt-6 space-y-3">
            {state.data.items.map((trip) => (
              <TripCard key={trip.id} trip={trip} />
            ))}
          </ul>
          <Pager label="Trip pages" page={state.data.page} totalPages={state.data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}
