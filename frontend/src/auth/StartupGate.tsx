import { useEffect, type ReactNode } from 'react';
import { useCountdown } from '../hooks/useCountdown';
import { useAuthStore } from '../stores/authStore';

/** When the server sent no Retry-After, how long to wait before trying the start-up refresh again. */
const STARTUP_RETRY_SECONDS = 10;

/**
 * Holds the whole app back until the start-up refresh has answered. While
 * it is in flight nothing renders that depends on who is signed in, so a
 * reload never flashes the logged-out header or bounces through /login.
 *
 * If the refresh got no answer about the session (server down, network),
 * the user is neither signed in nor out: this waits and retries, and the
 * session, which may well still be valid, is kept.
 */
export function StartupGate({ children }: { children: ReactNode }) {
  const status = useAuthStore((state) => state.status);

  if (status === 'starting') {
    return (
      <div role="status" className="flex min-h-screen items-center justify-center bg-slate-50 text-slate-600">
        Loading TripMates…
      </div>
    );
  }
  if (status === 'unavailable') {
    return <StartupUnavailable />;
  }
  return children;
}

function StartupUnavailable() {
  const retryAfter = useAuthStore((state) => state.startupRetryAfterSeconds);
  const restoreSession = useAuthStore((state) => state.restoreSession);
  const { secondsLeft, start } = useCountdown();
  const waitSeconds = retryAfter ?? STARTUP_RETRY_SECONDS;

  useEffect(() => {
    start(waitSeconds);
    const timer = setTimeout(() => {
      void restoreSession();
    }, waitSeconds * 1000);
    return () => {
      clearTimeout(timer);
    };
  }, [waitSeconds, start, restoreSession]);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-slate-50 px-4 text-center text-slate-900">
      <h1 className="text-2xl font-bold">TripMates is temporarily unavailable</h1>
      <p role="status" className="mt-2 text-slate-600">
        {secondsLeft > 0
          ? `Trying again in ${String(secondsLeft)} ${secondsLeft === 1 ? 'second' : 'seconds'}.`
          : 'Trying again…'}
      </p>
      <button
        type="button"
        onClick={() => {
          void restoreSession();
        }}
        className="mt-4 rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800"
      >
        Try now
      </button>
    </div>
  );
}
