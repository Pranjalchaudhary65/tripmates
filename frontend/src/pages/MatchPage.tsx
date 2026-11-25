import { useCallback, useState, type SubmitEvent } from 'react';
import { Link, useParams } from 'react-router';
import { meetupApi, type CancellationType, type Match, type Meetup, type VenueOption } from '../api/meetupApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { inputClassName } from '../components/FormField';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';
import { midpoint } from '../meetup/geo';
import {
  CATEGORY_LABELS,
  COMFORT_LABELS,
  formatVenueClock,
  formatVenueDay,
  formatVenueTime,
  STAGE_HEADINGS,
  startTimes,
} from '../meetup/meetupLabels';
import { isUuid } from '../trips/ids';
import { PLACES, placeById } from '../trips/places';
import { PERSON_TIER_LABELS } from '../trips/tripLabels';
import { NOTES_MAX_LENGTH, validateComfort } from '../validation/meetupRules';

type Runner = (action: () => Promise<Match>) => void;

/**
 * One match's trust ladder after acceptance: the video call, a public
 * daylight meet-up at a verified venue, check-in, and a private comfort
 * rating. Each stage shows only what can be done next. A match that stopped
 * shows the same plain screen to both people, with no reason.
 */
export function MatchPage() {
  const { matchId = '' } = useParams();
  if (!isUuid(matchId)) {
    return <Unavailable />;
  }
  return <MatchLoader key={matchId} matchId={matchId} />;
}

function MatchLoader({ matchId }: { matchId: string }) {
  const load = useCallback(() => meetupApi.match(matchId), [matchId]);
  const { state, reload } = useApiResource(load);
  if (state.kind === 'failed' && state.failure.status === 404) {
    return <Unavailable />;
  }
  return (
    <section className="max-w-3xl">
      {state.kind === 'loading' && (
        <>
          <h1 className="text-2xl font-bold text-teal-800">Meet-up</h1>
          <p role="status" className="mt-6 text-slate-600">
            Loading…
          </p>
        </>
      )}
      {state.kind === 'failed' && (
        <>
          <h1 className="text-2xl font-bold text-teal-800">Meet-up</h1>
          <div className="mt-6 space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        </>
      )}
      {state.kind === 'ready' && <MatchView initial={state.data} />}
    </section>
  );
}

function MatchView({ initial }: { initial: Match }) {
  const [match, setMatch] = useState(initial);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  const act: Runner = useCallback(
    (action) => {
      setFailure(null);
      void run(async () => {
        try {
          setMatch(await action());
        } catch (error) {
          setFailure(toApiFailure(error));
        }
      });
    },
    [run],
  );

  const other = match.otherPerson.firstName;
  return (
    <>
      <h1 className="text-2xl font-bold text-teal-800">{STAGE_HEADINGS[match.stage]}</h1>
      <p className="mt-1 text-sm text-slate-600">
        With {other} ({PERSON_TIER_LABELS[match.otherPerson.verificationTier]}) ·{' '}
        <Link to={`/trips/${match.trip.id}`} className="text-teal-700 underline">
          {match.trip.title}
        </Link>
      </p>
      {match.stage !== 'DID_NOT_PROCEED' && match.stage !== 'CLOSED' && <Ladder stage={match.stage} />}

      <div className="mt-6 space-y-4">
        <StageBody match={match} act={act} pending={pending} />
        {failure !== null && (
          <ErrorNotice
            message={failure.message}
            correlationId={failure.correlationId}
            retrySecondsLeft={failure.retryAfterSeconds}
          />
        )}
      </div>
    </>
  );
}

const LADDER_STEPS = ['Video call', 'Public meet-up', 'How it felt', 'Trip locked'] as const;

function Ladder({ stage }: { stage: Match['stage'] }) {
  const current =
    stage === 'VIDEO_CALL'
      ? 0
      : stage === 'FEEDBACK' || stage === 'WAITING_FOR_OTHER'
        ? 2
        : stage === 'LOCKED'
          ? 3
          : 1;
  return (
    <ol aria-label="Steps before the trip locks" className="mt-4 flex flex-wrap gap-2 text-xs">
      {LADDER_STEPS.map((step, index) => (
        <li
          key={step}
          aria-current={index === current ? 'step' : undefined}
          className={`rounded-full px-3 py-1 ${
            index < current ? 'bg-teal-100 text-teal-900' : index === current ? 'bg-teal-700 text-white' : 'bg-slate-100 text-slate-600'
          }`}
        >
          {step}
        </li>
      ))}
    </ol>
  );
}

function StageBody({ match, act, pending }: { match: Match; act: Runner; pending: boolean }) {
  const other = match.otherPerson.firstName;
  switch (match.stage) {
    case 'DID_NOT_PROCEED':
      return (
        <div className="rounded-md border border-slate-200 bg-white px-4 py-4 text-slate-700">
          <p>This match didn&apos;t proceed. That happens for all sorts of reasons, and we don&apos;t share them with anyone.</p>
          <p className="mt-2">Your plans are free again.</p>
          <Link to="/trips" className="mt-3 inline-block font-medium text-teal-700 underline">
            Find another trip
          </Link>
        </div>
      );
    case 'CLOSED':
      return <p className="text-slate-700">This match is no longer being arranged.</p>;
    case 'LOCKED':
      return (
        <div className="rounded-md border border-teal-200 bg-teal-50 px-4 py-4 text-teal-900">
          <p>You met {other} in person and you both felt comfortable. Your trip is locked.</p>
          <div className="mt-3 flex flex-wrap gap-4">
            <Link to="/messages" className="font-medium text-teal-700 underline">
              Contact details are now in your chat
            </Link>
            <Link to={`/trips/${match.trip.id}/settle-up`} className="font-medium text-teal-700 underline">
              Shared expenses and settle-up
            </Link>
            <Link to={`/trips/${match.trip.id}/live`} className="font-medium text-teal-700 underline">
              Live trip, check-ins and SOS
            </Link>
          </div>
        </div>
      );
    case 'VIDEO_CALL':
      return <VideoCallStep match={match} act={act} pending={pending} />;
    case 'ARRANGE_MEETUP':
      return match.emergencyContactSet ? (
        <ProposeStep match={match} act={act} pending={pending} />
      ) : (
        <EmergencyContactNeeded />
      );
    case 'AWAITING_CONFIRMATION':
    case 'CONFIRMED':
      return match.meetup === null ? null : <MeetupStep match={match} meetup={match.meetup} act={act} pending={pending} />;
    case 'FEEDBACK':
      return <ComfortStep match={match} act={act} pending={pending} />;
    case 'WAITING_FOR_OTHER':
      return (
        <p className="text-slate-700">
          Thanks. Once {other} has answered too, we will lock your trip, or let you both know it didn&apos;t proceed.
          Nobody ever sees what you chose.
        </p>
      );
  }
}

function VideoCallStep({ match, act, pending }: { match: Match; act: Runner; pending: boolean }) {
  const call = match.videoCall;
  const other = match.otherPerson.firstName;
  return (
    <div className="space-y-3 text-slate-700">
      <p>
        Have a short video call with {other} before meeting, so you both know the other person is who they say they
        are. Once you have both confirmed it, you can arrange a meet-up.
      </p>
      <ul className="text-sm">
        <li>You: {call?.confirmedByMe === true ? 'confirmed' : 'not yet'}</li>
        <li>
          {other}: {call?.confirmedByThem === true ? 'confirmed' : 'not yet'}
        </li>
      </ul>
      {call?.confirmedByMe !== true && (
        <button
          type="button"
          disabled={pending}
          onClick={() => {
            act(() => meetupApi.confirmVideoCall(match.matchId));
          }}
          className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 disabled:bg-slate-400"
        >
          We had our video call
        </button>
      )}
    </div>
  );
}

function EmergencyContactNeeded() {
  return (
    <div className="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
      <p>Before arranging a meet-up, add an emergency contact. We only alert them if you go quiet at the meet-up.</p>
      <Link to="/account/emergency-contact" className="mt-2 inline-block font-medium text-teal-700 underline">
        Add an emergency contact
      </Link>
    </div>
  );
}

type SearchMode = { kind: 'trip' } | { kind: 'city'; city: string } | { kind: 'halfway'; mine: string; theirs: string };

function searchPoint(mode: SearchMode): { latitude: number; longitude: number; radiusKm: number } | null {
  if (mode.kind === 'city') {
    const place = placeById(mode.city);
    return place === null ? null : { latitude: place.latitude, longitude: place.longitude, radiusKm: 25 };
  }
  if (mode.kind === 'halfway') {
    const mine = placeById(mode.mine);
    const theirs = placeById(mode.theirs);
    if (mine === null || theirs === null) {
      return null;
    }
    return { ...midpoint(mine, theirs), radiusKm: 100 };
  }
  return null;
}

function ProposeStep({ match, act, pending }: { match: Match; act: Runner; pending: boolean }) {
  const [mode, setMode] = useState<SearchMode>({ kind: 'trip' });
  const near = searchPoint(mode);
  const nearKey = near === null ? 'trip' : `${String(near.latitude)},${String(near.longitude)},${String(near.radiusKm)}`;
  return (
    <div className="space-y-4">
      {match.meetup !== null && match.meetup.status !== 'COMPLETED' && (
        <p className="rounded-md bg-slate-100 px-4 py-2 text-sm text-slate-700">
          {match.meetup.status === 'NO_SHOW' ? 'The last meet-up did not happen.' : 'The last meet-up was called off.'}{' '}
          You can arrange another.
        </p>
      )}
      <p className="text-slate-700">
        Pick a verified public place and a daylight time. Only busy, staffed places are on the list, and times are
        limited to daylight at the venue.
      </p>
      <SearchPicker mode={mode} onChange={setMode} />
      <VenueChooser key={nearKey} match={match} near={near} act={act} pending={pending} />
    </div>
  );
}

function SearchPicker({ mode, onChange }: { mode: SearchMode; onChange: (mode: SearchMode) => void }) {
  const first = PLACES[0]?.id ?? '';
  return (
    <fieldset className="space-y-2 text-sm">
      <legend className="font-medium text-slate-800">Look for venues</legend>
      <label className="flex items-center gap-2">
        <input
          type="radio"
          name="search"
          checked={mode.kind === 'trip'}
          onChange={() => {
            onChange({ kind: 'trip' });
          }}
        />
        Near the trip&apos;s destination
      </label>
      <label className="flex items-center gap-2">
        <input
          type="radio"
          name="search"
          checked={mode.kind === 'city'}
          onChange={() => {
            onChange({ kind: 'city', city: first });
          }}
        />
        In a city
      </label>
      {mode.kind === 'city' && (
        <CitySelect
          label="City"
          value={mode.city}
          onChange={(city) => {
            onChange({ kind: 'city', city });
          }}
        />
      )}
      <label className="flex items-center gap-2">
        <input
          type="radio"
          name="search"
          checked={mode.kind === 'halfway'}
          onChange={() => {
            onChange({ kind: 'halfway', mine: first, theirs: first });
          }}
        />
        Halfway between where you both are
      </label>
      {mode.kind === 'halfway' && (
        <div className="grid gap-2 sm:grid-cols-2">
          <CitySelect
            label="Your city"
            value={mode.mine}
            onChange={(mine) => {
              onChange({ ...mode, mine });
            }}
          />
          <CitySelect
            label="Their city"
            value={mode.theirs}
            onChange={(theirs) => {
              onChange({ ...mode, theirs });
            }}
          />
        </div>
      )}
    </fieldset>
  );
}

function CitySelect({ label, value, onChange }: { label: string; value: string; onChange: (id: string) => void }) {
  return (
    <label className="block">
      <span className="text-slate-700">{label}</span>
      <select
        value={value}
        onChange={(event) => {
          onChange(event.target.value);
        }}
        className={inputClassName}
      >
        {PLACES.map((place) => (
          <option key={place.id} value={place.id}>
            {place.name}
          </option>
        ))}
      </select>
    </label>
  );
}

function VenueChooser({
  match,
  near,
  act,
  pending,
}: {
  match: Match;
  near: { latitude: number; longitude: number; radiusKm: number } | null;
  act: Runner;
  pending: boolean;
}) {
  // Fixed for this chooser's life; a new search point remounts it (see its key).
  const [point] = useState(near);
  const load = useCallback(() => meetupApi.venues(match.matchId, point), [match.matchId, point]);
  const { state, reload } = useApiResource(load);
  const [venueId, setVenueId] = useState<string | null>(null);
  const [date, setDate] = useState<string | null>(null);
  const [time, setTime] = useState<string | null>(null);
  const [plusOne, setPlusOne] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (state.kind === 'loading') {
    return (
      <p role="status" className="text-slate-600">
        Finding venues…
      </p>
    );
  }
  if (state.kind === 'failed') {
    return (
      <div className="space-y-2">
        <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
        <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
          Try again
        </button>
      </div>
    );
  }
  const options = state.data;
  if (options.length === 0) {
    return <p className="text-slate-700">No verified venues near there yet. Try another city, or the halfway point.</p>;
  }
  const chosen: VenueOption | undefined = options.find((option) => option.venue.id === venueId);
  const window = chosen?.windows.find((candidate) => candidate.date === date);

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (chosen === undefined || window === undefined || time === null) {
      setError('Choose a venue, a day and a time.');
      return;
    }
    setError(null);
    act(() => meetupApi.propose(match.matchId, { venueId: chosen.venue.id, scheduledAt: time, bringingPlusOne: plusOne }));
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <fieldset>
        <legend className="text-sm font-medium text-slate-800">Venue</legend>
        <ul className="mt-2 space-y-2">
          {options.map((option) => (
            <li key={option.venue.id}>
              <label className="flex items-start gap-3 rounded-md border border-slate-200 bg-white px-3 py-2">
                <input
                  type="radio"
                  name="venue"
                  checked={venueId === option.venue.id}
                  onChange={() => {
                    setVenueId(option.venue.id);
                    setDate(option.windows[0]?.date ?? null);
                    setTime(null);
                  }}
                  className="mt-1"
                />
                <span>
                  <span className="block font-medium text-slate-900">{option.venue.name}</span>
                  <span className="block text-xs text-slate-600">
                    {CATEGORY_LABELS[option.venue.category]} · {option.venue.address}, {option.venue.city} ·{' '}
                    {option.distanceKm < 1 ? 'under 1 km' : `${option.distanceKm.toFixed(0)} km`} away
                  </span>
                  {option.windows.length === 0 && (
                    <span className="block text-xs text-amber-800">No daylight times before your trip starts.</span>
                  )}
                </span>
              </label>
            </li>
          ))}
        </ul>
      </fieldset>
      {chosen !== undefined && chosen.windows.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block text-sm">
            <span className="font-medium text-slate-800">Day</span>
            <select
              value={date ?? ''}
              onChange={(event) => {
                setDate(event.target.value);
                setTime(null);
              }}
              className={inputClassName}
            >
              {chosen.windows.map((candidate) => (
                <option key={candidate.date} value={candidate.date}>
                  {formatVenueDay(candidate.earliest, chosen.timeZone)}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="font-medium text-slate-800">Start time (daylight only)</span>
            <select
              value={time ?? ''}
              onChange={(event) => {
                setTime(event.target.value === '' ? null : event.target.value);
              }}
              className={inputClassName}
            >
              <option value="">Choose…</option>
              {window !== undefined &&
                startTimes(window.earliest, window.latest).map((start) => (
                  <option key={start} value={start}>
                    {formatVenueClock(start, chosen.timeZone)}
                  </option>
                ))}
            </select>
          </label>
        </div>
      )}
      <label className="flex items-center gap-2 text-sm text-slate-700">
        <input
          type="checkbox"
          checked={plusOne}
          onChange={(event) => {
            setPlusOne(event.target.checked);
          }}
        />
        I am bringing a friend along
      </label>
      {error !== null && <p className="text-sm text-red-700">{error}</p>}
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 disabled:bg-slate-400"
      >
        {pending ? 'Sending…' : 'Propose this meet-up'}
      </button>
    </form>
  );
}

function MeetupStep({ match, meetup, act, pending }: { match: Match; meetup: Meetup; act: Runner; pending: boolean }) {
  const other = match.otherPerson.firstName;
  const [plusOne, setPlusOne] = useState(false);
  const confirmed = meetup.status === 'CONFIRMED';
  return (
    <div className="space-y-4">
      <MeetupCard meetup={meetup} otherFirstName={other} />
      {!confirmed && meetup.proposedByMe && <p className="text-slate-700">Waiting for {other} to confirm.</p>}
      {!confirmed && !meetup.proposedByMe && !match.emergencyContactSet && <EmergencyContactNeeded />}
      {!confirmed && !meetup.proposedByMe && match.emergencyContactSet && (
        <div className="space-y-2">
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={plusOne}
              onChange={(event) => {
                setPlusOne(event.target.checked);
              }}
            />
            I am bringing a friend along
          </label>
          <button
            type="button"
            disabled={pending}
            onClick={() => {
              act(() => meetupApi.confirm(match.matchId, plusOne));
            }}
            className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 disabled:bg-slate-400"
          >
            Confirm this meet-up
          </button>
        </div>
      )}
      {confirmed && <ConfirmedPanel match={match} meetup={meetup} act={act} pending={pending} />}
      <CancelPanel match={match} act={act} pending={pending} />
    </div>
  );
}

function MeetupCard({ meetup, otherFirstName }: { meetup: Meetup; otherFirstName: string }) {
  const zone = meetup.venue.timeZone;
  return (
    <div role="region" aria-label="Meet-up details" className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
      <p className="font-medium text-slate-900">{meetup.venue.name}</p>
      <p className="text-slate-600">
        {meetup.venue.address}, {meetup.venue.city}
      </p>
      <p className="mt-1 text-slate-800">{formatVenueTime(meetup.scheduledAt, zone)} (local time)</p>
      {(meetup.myPlusOne || meetup.theirPlusOne) && (
        <p className="mt-1 text-xs text-slate-600">
          {meetup.myPlusOne ? 'You are bringing a friend. ' : ''}
          {meetup.theirPlusOne ? `${otherFirstName} is bringing a friend.` : ''}
        </p>
      )}
    </div>
  );
}

function ConfirmedPanel({ match, meetup, act, pending }: { match: Match; meetup: Meetup; act: Runner; pending: boolean }) {
  const zone = meetup.venue.timeZone;
  const other = match.otherPerson.firstName;
  return (
    <div className="space-y-3">
      <div className="rounded-md bg-teal-50 px-4 py-3 text-sm text-teal-900">
        <p>
          Check in when you get there: from {formatVenueClock(meetup.checkInOpensAt, zone)} to{' '}
          {formatVenueClock(meetup.checkInClosesAt, zone)}. You will need your location and a quick selfie.
        </p>
        <p className="mt-1">
          You: {meetup.iArrived ? 'checked in' : 'not checked in yet'} · {other}:{' '}
          {meetup.theyArrived ? 'checked in' : 'not checked in yet'}
        </p>
        {!meetup.iArrived && (
          <Link
            to={`/matches/${match.matchId}/check-in`}
            className="mt-2 inline-block rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800"
          >
            Check in at the venue
          </Link>
        )}
      </div>
      <div role="region" aria-label="Safety" className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700">
        <p>
          If you have not checked in 30 minutes after the start, we will email you. After 60 minutes we alert your
          emergency contact. If you are fine but not going, tell us so we do not worry.
        </p>
        {meetup.iConfirmedSafe ? (
          <p className="mt-2 font-medium text-teal-800">You told us you are safe. No alerts will be sent for you.</p>
        ) : (
          !meetup.iArrived && (
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                act(() => meetupApi.markSafe(match.matchId));
              }}
              className="mt-2 font-medium text-teal-700 underline disabled:text-slate-400"
            >
              I&apos;m safe
            </button>
          )
        )}
        <p className="mt-2 text-xs">If you are in danger, call 112.</p>
      </div>
    </div>
  );
}

/**
 * Two ways out. Changing plans keeps the match open. Stepping back for safety
 * ends it for both, and the other person only ever learns that it did not
 * proceed; it asks for no reason and costs nothing.
 */
function CancelPanel({ match, act, pending }: { match: Match; act: Runner; pending: boolean }) {
  const [asking, setAsking] = useState<CancellationType | null>(null);
  const other = match.otherPerson.firstName;
  if (asking === null) {
    return (
      <div className="flex flex-wrap gap-4 text-sm">
        <button
          type="button"
          onClick={() => {
            setAsking('CONVENIENCE');
          }}
          className="font-medium text-slate-700 underline"
        >
          Change of plans
        </button>
        <button
          type="button"
          onClick={() => {
            setAsking('SAFETY');
          }}
          className="font-medium text-red-700 underline"
        >
          I don&apos;t feel comfortable going ahead
        </button>
      </div>
    );
  }
  return (
    <div role="alertdialog" aria-label="Call off the meet-up" className="rounded-md border border-slate-300 bg-white px-4 py-3 text-sm">
      <p className="text-slate-800">
        {asking === 'SAFETY'
          ? `This ends the match. ${other} will only see that it didn't proceed: no reason, and nothing about who decided. It costs you nothing.`
          : 'This calls off the meet-up. Either of you can propose a new one.'}
      </p>
      <div className="mt-3 flex gap-4">
        <button
          type="button"
          disabled={pending}
          onClick={() => {
            act(() => meetupApi.cancel(match.matchId, asking));
          }}
          className="rounded-md bg-red-700 px-3 py-1.5 font-semibold text-white disabled:bg-slate-400"
        >
          {asking === 'SAFETY' ? 'End the match' : 'Call it off'}
        </button>
        <button
          type="button"
          onClick={() => {
            setAsking(null);
          }}
          className="font-medium text-slate-700 underline"
        >
          Keep it
        </button>
      </div>
    </div>
  );
}

/** Private: never shown to the other person and never part of any score. */
function ComfortStep({ match, act, pending }: { match: Match; act: Runner; pending: boolean }) {
  const [rating, setRating] = useState<number | null>(null);
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = validateComfort(rating, notes);
    setError(problem);
    if (problem !== null || rating === null) {
      return;
    }
    act(() => meetupApi.rate(match.matchId, rating, notes));
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <p className="text-slate-700">
        How comfortable did you feel meeting {match.otherPerson.firstName}? Only you see this. It is never shown to
        them, never part of any rating, and you never have to explain it.
      </p>
      <fieldset>
        <legend className="text-sm font-medium text-slate-800">Comfort</legend>
        <div className="mt-2 flex flex-wrap gap-2">
          {[1, 2, 3, 4, 5].map((value) => (
            <label
              key={value}
              className={`cursor-pointer rounded-md border px-3 py-2 text-sm ${
                rating === value ? 'border-teal-700 bg-teal-50' : 'border-slate-300 bg-white'
              }`}
            >
              <input
                type="radio"
                name="comfort"
                value={value}
                checked={rating === value}
                onChange={() => {
                  setRating(value);
                  setError(null);
                }}
                className="sr-only"
              />
              {value} · {COMFORT_LABELS[value]}
            </label>
          ))}
        </div>
      </fieldset>
      <label className="block text-sm">
        <span className="font-medium text-slate-800">Notes for yourself (optional)</span>
        <textarea
          value={notes}
          maxLength={NOTES_MAX_LENGTH}
          rows={3}
          onChange={(event) => {
            setNotes(event.target.value);
          }}
          className={inputClassName}
        />
      </label>
      {error !== null && <p className="text-sm text-red-700">{error}</p>}
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 disabled:bg-slate-400"
      >
        {pending ? 'Saving…' : 'Save privately'}
      </button>
    </form>
  );
}

function Unavailable() {
  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-bold text-teal-800">Match not available</h1>
      <p className="mt-4 text-slate-700">This match does not exist, or you are not part of it.</p>
      <Link to="/messages" className="mt-3 inline-block font-medium text-teal-700 underline">
        Back to your messages
      </Link>
    </section>
  );
}
