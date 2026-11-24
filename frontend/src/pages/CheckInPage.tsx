import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { meetupApi, type Match, type Meetup } from '../api/meetupApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import {
  attachStream,
  CameraError,
  CameraNotReadyError,
  captureFrame,
  openFrontCamera,
  stopStream,
  type CameraProblem,
} from '../camera/camera';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { currentPosition, LocationError, type LocationProblem, type Position } from '../location/geolocation';
import { distanceMeters } from '../meetup/geo';
import { formatVenueClock } from '../meetup/meetupLabels';
import { isUuid } from '../trips/ids';

/** The server's geofence; the screen only uses it to tell people how close they look. */
const GEOFENCE_METERS = 100;

const LOCATION_MESSAGES: Record<LocationProblem, string> = {
  unsupported: 'This browser cannot share your location. Try a recent Chrome, Edge, Firefox or Safari on your phone.',
  denied: 'TripMates was not allowed to use your location. Allow it in your browser settings, then try again.',
  unavailable: 'Your location could not be found. Step outside or turn on precise location, then try again.',
  timeout: 'Finding your location took too long. Try again in a moment.',
};

const CAMERA_MESSAGES: Record<CameraProblem, string> = {
  unsupported: 'This browser cannot use a camera. Try a recent Chrome, Edge, Firefox or Safari.',
  denied: 'TripMates was not allowed to use the camera. Allow it in your browser settings, then try again.',
  unavailable: 'No camera could be started. Check that it is not in use by another app.',
};

/**
 * Arrival at a meet-up: the phone's location and a selfie taken now, from the
 * live camera (there is no file picker, so an old photo cannot stand in). The
 * screen shows roughly how far away the phone thinks it is; the server
 * measures the real distance and decides.
 */
export function CheckInPage() {
  const { matchId = '' } = useParams();
  const load = useCallback(
    () => (isUuid(matchId) ? meetupApi.match(matchId) : Promise.reject(new Error('Not a match ID.'))),
    [matchId],
  );
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold text-teal-800">Check in</h1>
      <div className="mt-6">
        {state.kind === 'loading' && isUuid(matchId) && (
          <p role="status" className="text-slate-600">
            Loading…
          </p>
        )}
        {(!isUuid(matchId) || (state.kind === 'failed' && state.failure.status === 404)) && (
          <p className="text-slate-700">This meet-up does not exist, or you are not part of it.</p>
        )}
        {isUuid(matchId) && state.kind === 'failed' && state.failure.status !== 404 && (
          <div className="space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        )}
        {state.kind === 'ready' && <CheckInFlow match={state.data} />}
      </div>
    </section>
  );
}

function CheckInFlow({ match }: { match: Match }) {
  const meetup = match.meetup;
  if (match.stage !== 'CONFIRMED' || meetup === null) {
    return (
      <div className="space-y-2 text-slate-700">
        <p>There is no confirmed meet-up to check in to.</p>
        <Link to={`/matches/${match.matchId}`} className="font-medium text-teal-700 underline">
          Back to the meet-up
        </Link>
      </div>
    );
  }
  if (meetup.iArrived) {
    return <Done match={match} completed={false} />;
  }
  return <Steps match={match} meetup={meetup} />;
}

type Notice =
  | { kind: 'location'; problem: LocationProblem }
  | { kind: 'camera'; problem: CameraProblem }
  | { kind: 'request'; failure: ApiFailure };

function Steps({ match, meetup }: { match: Match; meetup: Meetup }) {
  const [position, setPosition] = useState<Position | null>(null);
  const [locating, setLocating] = useState(false);
  const [selfie, setSelfie] = useState<Blob | null>(null);
  const [cameraOn, setCameraOn] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [sending, setSending] = useState(false);
  const [done, setDone] = useState<{ completed: boolean } | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  const stopCamera = useCallback(() => {
    stopStream(streamRef.current);
    streamRef.current = null;
    setCameraOn(false);
  }, []);

  useEffect(() => stopCamera, [stopCamera]);

  function locate() {
    setLocating(true);
    setNotice(null);
    currentPosition()
      .then(setPosition, (error: unknown) => {
        setNotice({ kind: 'location', problem: error instanceof LocationError ? error.problem : 'unavailable' });
      })
      .finally(() => {
        setLocating(false);
      });
  }

  function startCamera() {
    setNotice(null);
    openFrontCamera().then(
      (stream) => {
        streamRef.current = stream;
        setCameraOn(true);
      },
      (error: unknown) => {
        setNotice({ kind: 'camera', problem: error instanceof CameraError ? error.problem : 'unavailable' });
      },
    );
  }

  const attachPreview = useCallback((video: HTMLVideoElement | null) => {
    videoRef.current = video;
    const stream = streamRef.current;
    if (video !== null && stream !== null) {
      attachStream(video, stream).catch(() => {
        setNotice({ kind: 'camera', problem: 'unavailable' });
      });
    }
  }, []);

  function takeSelfie() {
    const video = videoRef.current;
    if (video === null) {
      return;
    }
    captureFrame(video).then(
      (frame) => {
        setSelfie(frame);
        stopCamera();
      },
      (error: unknown) => {
        if (!(error instanceof CameraNotReadyError)) {
          setNotice({ kind: 'camera', problem: 'unavailable' });
        }
      },
    );
  }

  function submit() {
    if (position === null || selfie === null) {
      return;
    }
    setSending(true);
    setNotice(null);
    meetupApi
      .checkIn(match.matchId, {
        latitude: position.latitude,
        longitude: position.longitude,
        accuracyMeters: position.accuracy,
        selfie,
      })
      .then(
        (result) => {
          setDone({ completed: result.meetupCompleted });
        },
        (error: unknown) => {
          setNotice({ kind: 'request', failure: toApiFailure(error) });
        },
      )
      .finally(() => {
        setSending(false);
      });
  }

  if (done !== null) {
    return <Done match={match} completed={done.completed} />;
  }

  const away = position === null ? null : distanceMeters(position, meetup.venue);
  const zone = meetup.venue.timeZone;
  return (
    <div className="space-y-6">
      <p className="text-slate-700">
        {meetup.venue.name}, {meetup.venue.address}. Check-in is open from {formatVenueClock(meetup.checkInOpensAt, zone)}{' '}
        to {formatVenueClock(meetup.checkInClosesAt, zone)}.
      </p>

      <div role="region" aria-label="Your location" className="space-y-2">
        <h2 className="text-lg font-semibold text-slate-900">1. Your location</h2>
        {position === null ? (
          <button
            type="button"
            onClick={locate}
            disabled={locating}
            className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800 disabled:bg-slate-400"
          >
            {locating ? 'Finding you…' : 'Share my location'}
          </button>
        ) : (
          <p className="text-sm text-slate-700">
            {away !== null && away <= GEOFENCE_METERS
              ? `You look close enough: about ${String(Math.round(away))} m from the venue.`
              : `You look about ${String(Math.round(away ?? 0))} m away. You need to be within ${String(GEOFENCE_METERS)} m of the venue.`}{' '}
            (Accurate to about {String(Math.round(position.accuracy))} m.){' '}
            <button type="button" onClick={locate} className="font-medium text-teal-700 underline">
              Update
            </button>
          </p>
        )}
      </div>

      <div role="region" aria-label="Your selfie" className="space-y-2">
        <h2 className="text-lg font-semibold text-slate-900">2. A quick selfie</h2>
        <p className="text-sm text-slate-600">
          Taken now with your camera. Only the TripMates safety team could ever see it, and it is deleted after 7 days.
        </p>
        {selfie !== null ? (
          <p className="text-sm text-teal-800">
            Selfie taken.{' '}
            <button
              type="button"
              onClick={() => {
                setSelfie(null);
                startCamera();
              }}
              className="font-medium text-teal-700 underline"
            >
              Retake
            </button>
          </p>
        ) : cameraOn ? (
          <div className="space-y-2">
            <video ref={attachPreview} aria-label="Camera preview" className="w-full max-w-sm rounded-md bg-black" />
            <button
              type="button"
              onClick={takeSelfie}
              className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800"
            >
              Take selfie
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={startCamera}
            className="rounded-md border border-teal-700 px-4 py-2 font-semibold text-teal-800 hover:bg-teal-50"
          >
            Open camera
          </button>
        )}
      </div>

      {notice !== null && (
        <ErrorNotice
          message={
            notice.kind === 'location'
              ? LOCATION_MESSAGES[notice.problem]
              : notice.kind === 'camera'
                ? CAMERA_MESSAGES[notice.problem]
                : notice.failure.message
          }
          correlationId={notice.kind === 'request' ? notice.failure.correlationId : null}
        />
      )}

      <button
        type="button"
        onClick={submit}
        disabled={position === null || selfie === null || sending}
        className="w-full rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800 disabled:cursor-not-allowed disabled:bg-slate-400"
      >
        {sending ? 'Checking in…' : 'Check in'}
      </button>
      <p className="text-xs text-slate-500">If you feel unsafe at any point, leave, go somewhere busy, and call 112.</p>
    </div>
  );
}

function Done({ match, completed }: { match: Match; completed: boolean }) {
  return (
    <div role="status" className="space-y-2 rounded-md border border-teal-200 bg-teal-50 px-4 py-4 text-teal-900">
      <p className="font-medium">You are checked in.</p>
      <p>
        {completed
          ? `${match.otherPerson.firstName} is here too. Enjoy the meet-up; afterwards, tell us privately how it felt.`
          : `We will let ${match.otherPerson.firstName} know you have arrived. Nothing else about you is shared.`}
      </p>
      <Link to={`/matches/${match.matchId}`} className="inline-block font-medium text-teal-700 underline">
        Back to the meet-up
      </Link>
    </div>
  );
}
