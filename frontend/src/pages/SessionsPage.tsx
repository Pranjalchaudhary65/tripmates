import { useState } from 'react';
import { identityApi, type ActiveSession } from '../api/identityApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { useAuthStore } from '../stores/authStore';

const NO_FIELDS: readonly never[] = [];

/** Module-level, so the loader is one stable function and the list loads once per visit. */
const loadSessions = () => identityApi.listSessions();

const WHEN = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

/**
 * The caller's signed-in sessions. Everything shown comes from the server
 * as plain text (device names from a fixed list, a formatted IP address,
 * timestamps) and is rendered as text; error text goes through the problem
 * normaliser like everywhere else.
 */
export function SessionsPage() {
  const { state, reload } = useApiResource(loadSessions);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Signed-in devices</h1>
      <p className="mt-2 text-sm text-slate-600">
        Every browser or app signed in to your account. If you see one you do not recognise, sign out everywhere
        and change your password.
      </p>

      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading your sessions…
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {state.kind === 'ready' && <SessionList sessions={state.data} />}

      <RevokeAll />
    </section>
  );
}

function SessionList({ sessions }: { sessions: readonly ActiveSession[] }) {
  if (sessions.length === 0) {
    return <p className="mt-6 text-slate-600">No signed-in devices.</p>;
  }
  return (
    <ul className="mt-6 divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
      {sessions.map((session) => (
        <li key={session.id} className="px-4 py-3" aria-current={session.current ? 'true' : undefined}>
          <p className="font-medium text-slate-900">
            {session.device}
            {session.current && (
              <span className="ml-2 rounded bg-teal-100 px-2 py-0.5 text-xs font-semibold text-teal-800">
                This device
              </span>
            )}
          </p>
          <p className="mt-1 text-sm text-slate-600">
            {session.ipAddress} · signed in {formatWhen(session.signedInAt)} · last active{' '}
            {formatWhen(session.lastActiveAt)}
          </p>
          <p className="text-xs text-slate-500">Signs out automatically {formatWhen(session.expiresAt)}</p>
        </li>
      ))}
    </ul>
  );
}

/**
 * Ends every session, this tab's included. The store clears this tab's
 * session on success, so the route guard takes the user to login with a
 * notice saying why.
 */
function RevokeAll() {
  const revokeAllDevices = useAuthStore((state) => state.revokeAllDevices);
  const [confirming, setConfirming] = useState(false);
  const { pending, run } = useSubmission();
  const server = useRequestFailure(NO_FIELDS);

  function revoke() {
    void run(async () => {
      const result = await revokeAllDevices();
      if (!result.ok) {
        server.report(result.failure);
      }
    });
  }

  return (
    <div className="mt-8 rounded-md border border-red-200 bg-white px-4 py-4">
      <h2 className="font-semibold text-slate-900">Sign out of every device</h2>
      <p className="mt-1 text-sm text-slate-600">
        Ends every session, including this one. You will need to log in again here.
      </p>
      {server.notice !== null && (
        <div className="mt-3">
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        </div>
      )}
      {confirming ? (
        <div className="mt-3 flex gap-3">
          <button
            type="button"
            onClick={revoke}
            disabled={pending || server.retrySecondsLeft > 0}
            className="rounded-md bg-red-700 px-4 py-2 font-semibold text-white hover:bg-red-800 disabled:bg-slate-400"
          >
            {pending ? 'Signing out…' : 'Yes, sign out everywhere'}
          </button>
          <button
            type="button"
            onClick={() => {
              setConfirming(false);
            }}
            className="px-4 py-2 font-medium text-slate-700 underline"
          >
            Cancel
          </button>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => {
            setConfirming(true);
          }}
          className="mt-3 rounded-md border border-red-300 px-4 py-2 font-semibold text-red-800 hover:bg-red-50"
        >
          Sign out of every device
        </button>
      )}
    </div>
  );
}

function formatWhen(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'at an unknown time' : WHEN.format(at);
}
