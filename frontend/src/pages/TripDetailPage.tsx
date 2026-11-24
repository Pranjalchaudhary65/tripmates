import { useCallback, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router';
import { isRecord } from '../api/accessGrant';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { tripsApi, type TripDetail, type TripSummary } from '../api/tripsApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { MatchBreakdown } from '../components/MatchBreakdown';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';
import {
  formatTripDates,
  PERSON_TIER_LABELS,
  placesLeft,
  REQUEST_STATUS_LABELS,
  TRIP_STATUS_LABELS,
} from '../trips/tripLabels';
import { isUuid } from '../trips/ids';
import { BUDGET_LABELS } from '../vibe/vibeLabels';

/**
 * One trip, in one call, with how well the caller matches it. Someone
 * else's open trip offers "Ask to join", unless a deal-breaker rules it out; the
 * host sees how many requests wait and goes to the inbox to answer them. An
 * ID that is not a UUID is never sent: it gets the same "not available"
 * screen the server's 404 gets, whether the trip does not exist or the
 * caller may not see it.
 */
export function TripDetailPage() {
  const { tripId = '' } = useParams();
  const validId = isUuid(tripId);
  const load = useCallback(
    () => (validId ? tripsApi.detail(tripId) : Promise.reject(new Error('Not a trip ID.'))),
    [tripId, validId],
  );
  const { state, reload } = useApiResource(load);
  const [joined, setJoined] = useState<TripSummary['myRequest']>(null);
  const location = useLocation();
  const justCreated = isRecord(location.state) && location.state['justCreated'] === true;

  if (!validId || (state.kind === 'failed' && state.failure.status === 404)) {
    return <Unavailable />;
  }
  return (
    <section className="max-w-3xl">
      {state.kind === 'loading' && (
        <>
          <h1 className="text-2xl font-bold text-teal-800">Trip</h1>
          <p role="status" className="mt-6 text-slate-600">
            Loading the trip…
          </p>
        </>
      )}
      {state.kind === 'failed' && (
        <>
          <h1 className="text-2xl font-bold text-teal-800">Trip</h1>
          <div className="mt-6 space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        </>
      )}
      {state.kind === 'ready' && (
        <TripView detail={state.data} justCreated={justCreated} joined={joined} onJoined={setJoined} />
      )}
    </section>
  );
}

function TripView({
  detail,
  justCreated,
  joined,
  onJoined,
}: {
  detail: TripDetail;
  justCreated: boolean;
  joined: TripSummary['myRequest'];
  onJoined: (request: TripSummary['myRequest']) => void;
}) {
  const trip = detail.trip;
  const myRequest = joined ?? trip.myRequest;
  return (
    <>
      <h1 className="text-2xl font-bold text-teal-800">{trip.title}</h1>
      {justCreated && (
        <p role="status" className="mt-4 rounded-md bg-teal-50 px-3 py-2 text-sm text-teal-900">
          Your trip is open. Travellers can find it and ask to join.
        </p>
      )}
      <dl className="mt-4 grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        <Row term="Where" value={trip.destinationName} />
        <Row term="When" value={formatTripDates(trip.startDate, trip.endDate)} />
        <Row term="Budget" value={BUDGET_LABELS[trip.budgetBand]} />
        <Row term="Places" value={`${placesLeft(trip.totalSlots, trip.filledSlots)} of ${String(trip.totalSlots)}`} />
        <Row term="Status" value={TRIP_STATUS_LABELS[trip.status]} />
        <Row term="Roaming" value={detail.radiusKm === 0 ? 'One place' : `Up to ${String(detail.radiusKm)} km around it`} />
        <Row
          term="Host"
          value={
            trip.hostedByMe ? 'You' : `${trip.host.firstName} (${PERSON_TIER_LABELS[trip.host.verificationTier]})`
          }
        />
      </dl>
      <p className="mt-6 whitespace-pre-line text-slate-800">{detail.description}</p>

      {detail.match !== null && (
        <div className="mt-6">
          <MatchBreakdown match={detail.match} />
        </div>
      )}

      <div className="mt-6">
        {trip.hostedByMe ? (
          <HostPanel trip={trip} />
        ) : myRequest !== null ? (
          <p role="status" className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
            Your request: <span className="font-medium">{REQUEST_STATUS_LABELS[myRequest.status]}</span>.{' '}
            <Link to="/trips/requests" className="font-medium text-teal-700 underline">
              See all your requests
            </Link>
          </p>
        ) : detail.match?.status === 'BLOCKED' ? null : trip.status === 'OPEN' ? (
          <JoinButton tripId={trip.id} onJoined={onJoined} />
        ) : (
          <p className="text-sm text-slate-600">This trip is not taking requests.</p>
        )}
      </div>
    </>
  );
}

function HostPanel({ trip }: { trip: TripSummary }) {
  const waiting = trip.pendingRequests ?? 0;
  return (
    <p className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
      You are hosting this trip.{' '}
      {waiting === 0 ? 'No requests are waiting.' : `${String(waiting)} waiting for your answer.`}{' '}
      <Link to={`/trips/requests?trip=${trip.id}`} className="font-medium text-teal-700 underline">
        Open the requests
      </Link>
    </p>
  );
}

function JoinButton({ tripId, onJoined }: { tripId: string; onJoined: (request: TripSummary['myRequest']) => void }) {
  const { pending, run } = useSubmission();
  const [failure, setFailure] = useState<ApiFailure | null>(null);

  function join() {
    setFailure(null);
    void run(async () => {
      try {
        const created = await tripsApi.requestToJoin(tripId);
        onJoined({ id: created.id, status: created.status });
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  return (
    <div className="space-y-3">
      <button
        type="button"
        onClick={join}
        disabled={pending}
        aria-busy={pending}
        className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 disabled:cursor-not-allowed disabled:bg-slate-400"
      >
        {pending ? 'Sending…' : 'Ask to join'}
      </button>
      <p className="text-xs text-slate-500">The host sees your first name and verification level, nothing else.</p>
      {failure !== null && (
        <ErrorNotice
          message={failure.message}
          correlationId={failure.correlationId}
          retrySecondsLeft={failure.retryAfterSeconds}
        />
      )}
    </div>
  );
}

function Unavailable() {
  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-bold text-teal-800">Trip not available</h1>
      <p className="mt-4 text-slate-700">This trip does not exist, or is no longer open to you.</p>
      <Link to="/trips" className="mt-3 inline-block font-medium text-teal-700 underline">
        Find another trip
      </Link>
    </section>
  );
}

function Row({ term, value }: { term: string; value: string }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-100 py-1">
      <dt className="text-slate-600">{term}</dt>
      <dd className="text-right font-medium text-slate-900">{value}</dd>
    </div>
  );
}
