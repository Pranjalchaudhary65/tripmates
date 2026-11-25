import { useCallback, useState, type SubmitEvent } from 'react';
import { Link, useParams } from 'react-router';
import { toApiFailure, type ApiFailure } from '../api/problem';
import {
  CHECK_IN_INTERVALS,
  safetyApi,
  type CheckInInterval,
  type LiveTrip,
  type Premortem,
  type ReportedLocation,
} from '../api/safetyApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { inputClassName } from '../components/FormField';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';
import { currentPosition } from '../location/geolocation';
import { isUuid } from '../trips/ids';

/** An SOS never waits long for a location: it goes without one after this. */
export const SOS_LOCATION_WAIT_MS = 5_000;

/** Beyond this from the destination, a check-in is shown as outside the trip area. */
const TRIP_AREA_KM = 50;

const TIME = new Intl.DateTimeFormat('en-IN', { weekday: 'short', hour: 'numeric', minute: '2-digit' });

/**
 * Live trip mode: sharing the trip with the user's emergency contact, the
 * next "are you safe?" check-in, the SOS button, who else is on the trip,
 * and before it starts, the conversations worth having. Every action here is
 * free; none of them touches anyone's trust score.
 */
export function LiveTripPage() {
  const { tripId = '' } = useParams();
  const valid = isUuid(tripId);
  const load = useCallback(
    () => (valid ? safetyApi.live(tripId) : Promise.reject(new Error('Not a trip.'))),
    [tripId, valid],
  );
  const { state, reload } = useApiResource(load);

  if (!valid || (state.kind === 'failed' && state.failure.status === 404)) {
    return (
      <section className="max-w-xl">
        <h1 className="text-2xl font-bold text-teal-800">Live trip not available</h1>
        <p className="mt-2 text-slate-600">
          This trip doesn&apos;t exist, or you aren&apos;t on it. Live trip mode is for the host and the companions who
          met them in person.
        </p>
        <Link to="/trips" className="mt-4 inline-block font-medium text-teal-700 underline">
          Back to trips
        </Link>
      </section>
    );
  }
  if (state.kind === 'loading') {
    return (
      <p role="status" className="text-slate-600">
        Loading…
      </p>
    );
  }
  if (state.kind === 'failed') {
    return (
      <div className="space-y-3">
        <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
        <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
          Try again
        </button>
      </div>
    );
  }
  return <LiveTripView initial={state.data} />;
}

function LiveTripView({ initial }: { initial: LiveTrip }) {
  const [live, setLive] = useState(initial);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  const act = useCallback(
    (action: () => Promise<LiveTrip>) => {
      setFailure(null);
      void run(async () => {
        try {
          setLive(await action());
        } catch (error) {
          setFailure(toApiFailure(error));
        }
      });
    },
    [run],
  );

  return (
    <section className="max-w-3xl space-y-8">
      <header>
        <h1 className="text-2xl font-bold text-teal-800">Live trip: {live.trip.title}</h1>
        <p className="mt-1 text-sm text-slate-600">
          {live.trip.destinationName} · {live.trip.startDate} to {live.trip.endDate} ·{' '}
          {live.phase === 'BEFORE' ? 'not started yet' : live.phase === 'ACTIVE' ? 'on now' : 'over'}
        </p>
      </header>

      <SosPanel live={live} onChange={setLive} />

      {failure !== null && <ErrorNotice message={failure.message} correlationId={failure.correlationId} />}

      {!live.locked ? (
        <p className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700">
          Check-ins start once the trip is locked, after you have met a companion in person. SOS works now.
        </p>
      ) : (
        <>
          <CheckInPanel live={live} act={act} pending={pending} />
          <SharingPanel live={live} act={act} pending={pending} />
        </>
      )}

      <Buddies live={live} />

      {live.locked && live.phase === 'BEFORE' && <PremortemPanel tripId={live.trip.id} />}

      <nav aria-label="More about this trip" className="flex flex-wrap gap-4 text-sm">
        <Link to={`/trips/${live.trip.id}/settle-up`} className="font-medium text-teal-700 underline">
          Shared expenses
        </Link>
        <Link to="/account/emergency-contact" className="font-medium text-teal-700 underline">
          Emergency contact
        </Link>
      </nav>
    </section>
  );
}

/** Location for an SOS, or null: it never holds the SOS up for long, and never fails it. */
async function sosLocation(): Promise<ReportedLocation | null> {
  const timeout = new Promise<null>((resolve) => {
    setTimeout(() => {
      resolve(null);
    }, SOS_LOCATION_WAIT_MS);
  });
  const located = currentPosition().then(
    (position) => ({ latitude: position.latitude, longitude: position.longitude, accuracyMeters: position.accuracy }),
    () => null,
  );
  return Promise.race([located, timeout]);
}

function SosPanel({ live, onChange }: { live: LiveTrip; onChange: (live: LiveTrip) => void }) {
  const [confirming, setConfirming] = useState(false);
  /** One ID per press, reused if the request has to be retried, so one press is one SOS. */
  const [clientSosId, setClientSosId] = useState(() => crypto.randomUUID());
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  function send() {
    setFailure(null);
    void run(async () => {
      try {
        const location = await sosLocation();
        onChange(await safetyApi.sos(live.trip.id, clientSosId, location));
        setConfirming(false);
        setClientSosId(crypto.randomUUID());
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  function resolve(sosId: string) {
    setFailure(null);
    void run(async () => {
      try {
        onChange(await safetyApi.resolveSos(sosId));
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  if (live.openSos !== null) {
    const sos = live.openSos;
    return (
      <div role="region" aria-label="SOS" className="rounded-md border-2 border-red-600 bg-red-50 px-4 py-4">
        <h2 className="text-lg font-bold text-red-800">SOS sent at {TIME.format(new Date(sos.triggeredAt))}</h2>
        <p className="mt-1 text-sm text-red-900">
          {sos.contactAlerted
            ? 'Your emergency contact has been alerted with your location and trip details. TripMates moderators can see it too.'
            : 'We could not reach your emergency contact yet and are still trying. TripMates moderators can see it.'}
        </p>
        <a href="tel:112" className="mt-3 inline-block rounded-md bg-red-700 px-4 py-2 font-bold text-white">
          Call 112
        </a>
        <button
          type="button"
          disabled={pending}
          onClick={() => {
            resolve(sos.id);
          }}
          className="ml-4 font-medium text-red-800 underline disabled:opacity-50"
        >
          I&apos;m safe now
        </button>
        {failure !== null && (
          <div className="mt-3">
            <ErrorNotice message={failure.message} correlationId={failure.correlationId} />
          </div>
        )}
      </div>
    );
  }

  return (
    <div role="region" aria-label="SOS" className="rounded-md border border-red-200 bg-white px-4 py-4">
      {!confirming ? (
        <button
          type="button"
          onClick={() => {
            setConfirming(true);
          }}
          className="w-full rounded-md bg-red-700 px-4 py-4 text-xl font-bold text-white hover:bg-red-800"
        >
          SOS
        </button>
      ) : (
        <div className="space-y-3">
          <p className="font-medium text-red-900">
            Send an SOS? Your emergency contact gets your location, your trip and who you are with. The other
            travellers are not told. It costs you nothing.
          </p>
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              disabled={pending}
              onClick={send}
              className="rounded-md bg-red-700 px-4 py-2 font-bold text-white disabled:opacity-50"
            >
              {pending ? 'Sending…' : 'Send SOS now'}
            </button>
            <a href="tel:112" className="rounded-md border border-red-700 px-4 py-2 font-bold text-red-800">
              Call 112
            </a>
            <button
              type="button"
              onClick={() => {
                setConfirming(false);
              }}
              className="font-medium text-slate-700 underline"
            >
              Cancel
            </button>
          </div>
          {failure !== null && (
            <>
              <ErrorNotice message={failure.message} correlationId={failure.correlationId} />
              <p className="text-sm font-medium text-red-900">If you are in danger, call 112 now.</p>
            </>
          )}
        </div>
      )}
    </div>
  );
}

type Act = (action: () => Promise<LiveTrip>) => void;

function CheckInPanel({ live, act, pending }: { live: LiveTrip; act: Act; pending: boolean }) {
  const [withLocation, setWithLocation] = useState(true);
  const [locationNote, setLocationNote] = useState<string | null>(null);

  if (live.sharing === null) {
    return null;
  }

  function checkIn() {
    setLocationNote(null);
    act(async () => {
      let location: ReportedLocation | null = null;
      if (withLocation) {
        try {
          const position = await currentPosition();
          location = { latitude: position.latitude, longitude: position.longitude, accuracyMeters: position.accuracy };
        } catch {
          setLocationNote('Your location could not be read, so this check-in was sent without it.');
        }
      }
      return safetyApi.checkIn(live.trip.id, location);
    });
  }

  const next = live.nextCheckIn;
  const last = live.lastCheckIn;
  return (
    <div className="rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="text-lg font-semibold text-slate-900">Check-ins</h2>
      {next === null ? (
        <p className="mt-1 text-sm text-slate-600">No check-in is scheduled.</p>
      ) : next.overdue ? (
        <p role="alert" className="mt-2 rounded bg-amber-50 px-3 py-2 text-sm font-medium text-amber-900">
          Your check-in was due at {TIME.format(new Date(next.dueAt))}. Tap &quot;I&apos;m safe&quot; now, or your
          emergency contact will be alerted.
        </p>
      ) : (
        <p className="mt-1 text-sm text-slate-600">Next check-in due {TIME.format(new Date(next.dueAt))}.</p>
      )}
      <label className="mt-3 flex items-center gap-2 text-sm text-slate-700">
        <input
          type="checkbox"
          checked={withLocation}
          onChange={(event) => {
            setWithLocation(event.target.checked);
          }}
        />
        Include my location (only your emergency contact can ever see it, and only in an alert)
      </label>
      <button
        type="button"
        disabled={pending}
        onClick={checkIn}
        className="mt-3 rounded-md bg-teal-700 px-4 py-2 font-medium text-white disabled:opacity-50"
      >
        I&apos;m safe
      </button>
      {locationNote !== null && <p className="mt-2 text-sm text-slate-600">{locationNote}</p>}
      {last !== null && (
        <p className="mt-3 text-sm text-slate-600">
          Last check-in {TIME.format(new Date(last.at))}
          {last.kmFromDestination === null
            ? ', without a location.'
            : last.kmFromDestination > TRIP_AREA_KM
              ? `, about ${last.kmFromDestination.toFixed(1)} km from ${live.trip.destinationName}: outside the trip area.`
              : `, about ${last.kmFromDestination.toFixed(1)} km from ${live.trip.destinationName}.`}
        </p>
      )}
    </div>
  );
}

function SharingPanel({ live, act, pending }: { live: LiveTrip; act: Act; pending: boolean }) {
  const [every, setEvery] = useState<CheckInInterval>(live.sharing?.checkInEveryHours ?? 12);
  const [note, setNote] = useState('');
  const [noteError, setNoteError] = useState<string | null>(null);

  if (live.phase === 'ENDED') {
    return <p className="text-sm text-slate-600">This trip is over; no more check-ins.</p>;
  }
  if (!live.emergencyContactSet) {
    return (
      <div className="rounded-md border border-slate-200 bg-white px-4 py-4 text-sm">
        <p className="text-slate-700">
          To get check-ins, add an emergency contact first. They are told only if you miss a check-in or press SOS.
        </p>
        <Link to="/account/emergency-contact" className="mt-2 inline-block font-medium text-teal-700 underline">
          Add an emergency contact
        </Link>
      </div>
    );
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (note.trim().length > 300) {
      setNoteError('Keep the note to 300 characters.');
      return;
    }
    setNoteError(null);
    act(() => safetyApi.share(live.trip.id, every, note));
  }

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label="Trip sharing"
      className="space-y-3 rounded-md border border-slate-200 bg-white px-4 py-4"
    >
      <h2 className="text-lg font-semibold text-slate-900">
        {live.sharing === null ? 'Share this trip with your emergency contact' : 'Sharing is on'}
      </h2>
      <p className="text-sm text-slate-600">
        We ask you &quot;are you safe?&quot; at the interval you choose. If you don&apos;t answer within an hour, your
        emergency contact is alerted. Stopping is free and needs no reason.
      </p>
      <label className="block text-sm font-medium text-slate-800" htmlFor="check-in-every">
        Check in every
      </label>
      <select
        id="check-in-every"
        value={every}
        onChange={(event) => {
          setEvery(CHECK_IN_INTERVALS.find((value) => String(value) === event.target.value) ?? 12);
        }}
        className={inputClassName}
      >
        {CHECK_IN_INTERVALS.map((value) => (
          <option key={value} value={value}>
            {value} hours
          </option>
        ))}
      </select>
      <label className="block text-sm font-medium text-slate-800" htmlFor="sharing-note">
        Note for your emergency contact (optional)
      </label>
      <textarea
        id="sharing-note"
        value={note}
        maxLength={300}
        rows={2}
        aria-invalid={noteError !== null}
        placeholder={live.sharing?.noteSet ? 'A note is saved. Type a new one to replace it.' : 'Where you are staying, who to ask for'}
        onChange={(event) => {
          setNote(event.target.value);
        }}
        className={inputClassName}
      />
      {noteError !== null && <p className="text-sm text-red-700">{noteError}</p>}
      <div className="flex flex-wrap gap-4">
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-teal-700 px-4 py-2 font-medium text-white disabled:opacity-50"
        >
          {live.sharing === null ? 'Start sharing' : 'Save changes'}
        </button>
        {live.sharing !== null && (
          <button
            type="button"
            disabled={pending}
            onClick={() => {
              act(() => safetyApi.stopSharing(live.trip.id));
            }}
            className="font-medium text-slate-700 underline disabled:opacity-50"
          >
            Stop sharing
          </button>
        )}
      </div>
    </form>
  );
}

function Buddies({ live }: { live: LiveTrip }) {
  if (live.buddies.length === 0) {
    return null;
  }
  return (
    <div>
      <h2 className="text-lg font-semibold text-slate-900">Travelling with</h2>
      <ul aria-label="Travelling with" className="mt-2 space-y-1 text-sm text-slate-700">
        {live.buddies.map((buddy, index) => (
          <li key={`${buddy.firstName}-${String(index)}`}>
            {buddy.firstName}
            {buddy.role === 'HOST' ? ' (host)' : ''} ·{' '}
            {buddy.lastCheckInAt === null
              ? buddy.sharing
                ? 'sharing, no check-in yet'
                : 'not sharing'
              : `last checked in ${TIME.format(new Date(buddy.lastCheckInAt))}`}
          </li>
        ))}
      </ul>
    </div>
  );
}

function PremortemPanel({ tripId }: { tripId: string }) {
  const load = useCallback(() => safetyApi.premortem(tripId), [tripId]);
  const { state, reload } = useApiResource<Premortem>(load);

  return (
    <div role="region" aria-label="Before you go" className="rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="text-lg font-semibold text-slate-900">Before you go: talk these through</h2>
      {state.kind === 'loading' && (
        <p role="status" className="mt-2 text-sm text-slate-600">
          Loading…
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="mt-2 space-y-2">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="text-sm font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {state.kind === 'ready' && !state.data.myVibeSet && (
        <p className="mt-2 text-sm text-slate-700">
          <Link to="/vibe/quiz" className="font-medium text-teal-700 underline">
            Take the vibe quiz
          </Link>{' '}
          to see where you and your companions might clash.
        </p>
      )}
      {state.kind === 'ready' && state.data.myVibeSet && (
        <ul className="mt-3 space-y-4">
          {state.data.pairs.map((pair, index) => (
            <li key={`${pair.firstName}-${String(index)}`}>
              <h3 className="font-medium text-slate-900">You and {pair.firstName}</h3>
              {!pair.vibeSet ? (
                <p className="text-sm text-slate-600">{pair.firstName} hasn&apos;t taken the vibe quiz yet.</p>
              ) : pair.topics.length === 0 ? (
                <p className="text-sm text-slate-600">Nothing stands out. Enjoy the trip.</p>
              ) : (
                <ul className="mt-1 list-disc space-y-2 pl-5 text-sm">
                  {pair.topics.map((topic) => (
                    <li key={topic.dimension}>
                      <span className="text-slate-800">{topic.gap}</span>{' '}
                      <span className="text-slate-600">{topic.question}</span>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
