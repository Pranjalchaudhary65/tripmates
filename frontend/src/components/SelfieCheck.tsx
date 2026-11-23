import { useCallback, useEffect, useRef, useState } from 'react';
import { toApiFailure, type ApiFailure } from '../api/problem';
import {
  verificationApi,
  type LivenessPrompt,
  type SelfieChallenge,
  type VerificationOverview,
} from '../api/verificationApi';
import {
  attachStream,
  CameraError,
  CameraNotReadyError,
  captureFrame,
  openFrontCamera,
  stopStream,
  type CameraProblem,
} from '../camera/camera';
import { useCountdown } from '../hooks/useCountdown';
import { ErrorNotice } from './ErrorNotice';

const PROMPT_TEXT: Record<LivenessPrompt, string> = {
  TURN_HEAD_LEFT: 'turn your head a little to your left',
  TURN_HEAD_RIGHT: 'turn your head a little to your right',
  SMILE: 'smile',
  BLINK: 'close your eyes for a moment',
};

const CAMERA_MESSAGES: Record<CameraProblem, string> = {
  unsupported: 'This browser cannot use a camera. Try a recent Chrome, Edge, Firefox or Safari.',
  denied: 'TripMates was not allowed to use the camera. Allow it in your browser settings, then try again.',
  unavailable: 'No camera could be started. Check that one is connected and not in use by another app.',
};

const EXPIRED_MESSAGE = 'This selfie check timed out. Start a new one when you are ready.';

type Phase =
  | { kind: 'idle' }
  | { kind: 'starting' }
  | { kind: 'neutral'; challenge: SelfieChallenge }
  | { kind: 'prompt'; challenge: SelfieChallenge; neutral: Blob }
  | { kind: 'submitting' };

type Notice = { kind: 'camera'; problem: CameraProblem } | { kind: 'expired' } | { kind: 'request'; failure: ApiFailure };

/**
 * A live two-frame selfie check. The camera opens first, so a refused
 * permission costs no attempt; then the server picks a prompt and starts the
 * clock. Frame one looks at the camera, frame two follows the prompt, and
 * both go up together. The frames live in this component only, and the
 * camera stops on every way out: sent, failed, timed out or navigated away.
 */
export function SelfieCheck({ onSubmitted }: { onSubmitted: (overview: VerificationOverview) => void }) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const expiryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [notice, setNotice] = useState<Notice | null>(null);
  const [warmingUp, setWarmingUp] = useState(false);
  const { secondsLeft, start: startCountdown } = useCountdown();
  const { secondsLeft: retrySecondsLeft, start: startRetry } = useCountdown();

  const stopCamera = useCallback(() => {
    if (expiryTimer.current !== null) {
      clearTimeout(expiryTimer.current);
      expiryTimer.current = null;
    }
    stopStream(streamRef.current);
    streamRef.current = null;
  }, []);

  useEffect(() => stopCamera, [stopCamera]);

  const end = useCallback(
    (next: Notice | null) => {
      stopCamera();
      setPhase({ kind: 'idle' });
      setNotice(next);
      if (next?.kind === 'request' && next.failure.retryAfterSeconds !== null) {
        startRetry(next.failure.retryAfterSeconds);
      }
    },
    [stopCamera, startRetry],
  );

  /** Called by React when the preview appears: the stream is already open by then. */
  const attachPreview = useCallback(
    (video: HTMLVideoElement | null) => {
      videoRef.current = video;
      const stream = streamRef.current;
      if (video !== null && stream !== null) {
        attachStream(video, stream).catch(() => {
          end({ kind: 'camera', problem: 'unavailable' });
        });
      }
    },
    [end],
  );

  async function begin() {
    setNotice(null);
    setPhase({ kind: 'starting' });
    try {
      streamRef.current = await openFrontCamera();
      const challenge = await verificationApi.startSelfie();
      expiryTimer.current = setTimeout(() => {
        end({ kind: 'expired' });
      }, challenge.expiresInSeconds * 1000);
      startCountdown(challenge.expiresInSeconds);
      setPhase({ kind: 'neutral', challenge });
    } catch (error) {
      end(error instanceof CameraError ? { kind: 'camera', problem: error.problem } : requestNotice(error));
    }
  }

  async function takeFrame() {
    const video = videoRef.current;
    if (video === null || (phase.kind !== 'neutral' && phase.kind !== 'prompt')) {
      return;
    }
    let frame: Blob;
    try {
      frame = await captureFrame(video);
    } catch (error) {
      if (error instanceof CameraNotReadyError) {
        setWarmingUp(true);
      } else {
        end({ kind: 'camera', problem: 'unavailable' });
      }
      return;
    }
    setWarmingUp(false);
    if (phase.kind === 'neutral') {
      setPhase({ kind: 'prompt', challenge: phase.challenge, neutral: frame });
      return;
    }
    const { challenge, neutral } = phase;
    stopCamera();
    setPhase({ kind: 'submitting' });
    try {
      const overview = await verificationApi.submitSelfie(challenge.challengeId, neutral, frame);
      setPhase({ kind: 'idle' });
      onSubmitted(overview);
    } catch (error) {
      end(requestNotice(error));
    }
  }

  return (
    <div className="mt-3 space-y-3">
      {notice?.kind === 'camera' && <ErrorNotice message={CAMERA_MESSAGES[notice.problem]} />}
      {notice?.kind === 'expired' && <ErrorNotice message={EXPIRED_MESSAGE} />}
      {notice?.kind === 'request' && (
        <ErrorNotice
          message={notice.failure.message}
          correlationId={notice.failure.correlationId}
          retrySecondsLeft={notice.failure.retryAfterSeconds === null ? null : retrySecondsLeft}
        />
      )}

      {(phase.kind === 'neutral' || phase.kind === 'prompt') && (
        <div className="space-y-2">
          <video
            ref={attachPreview}
            aria-label="Camera preview"
            className="w-full max-w-sm -scale-x-100 rounded-md bg-slate-900"
          />
          <p role="status" className="font-medium text-slate-900">
            {phase.kind === 'neutral'
              ? 'Step 1 of 2: look straight at the camera.'
              : `Step 2 of 2: ${PROMPT_TEXT[phase.challenge.prompt]}.`}
          </p>
          <p className="text-sm text-slate-600">Time left: {secondsLeft}s</p>
          {warmingUp && <p className="text-sm text-slate-600">The camera is still starting. Try again in a moment.</p>}
          <button
            type="button"
            onClick={() => void takeFrame()}
            className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800"
          >
            {phase.kind === 'neutral' ? 'Take the first photo' : 'Take the second photo and send'}
          </button>
        </div>
      )}

      {phase.kind === 'submitting' && (
        <p role="status" className="text-slate-600">
          Checking your selfie…
        </p>
      )}

      {(phase.kind === 'idle' || phase.kind === 'starting') && (
        <button
          type="button"
          onClick={() => void begin()}
          disabled={phase.kind === 'starting' || retrySecondsLeft > 0}
          aria-busy={phase.kind === 'starting'}
          className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800 disabled:bg-slate-400"
        >
          {phase.kind === 'starting' ? 'Starting the camera…' : 'Start the selfie check'}
        </button>
      )}
    </div>
  );
}

function requestNotice(error: unknown): Notice {
  return { kind: 'request', failure: toApiFailure(error) };
}
