import { useCallback, useState } from 'react';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { trustApi } from '../api/trustApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { formatDay } from '../trust/trustLabels';

/** People the user has blocked, with a way to lift each block. */
export function BlockedPage() {
  const load = useCallback(() => trustApi.blocks(), []);
  const { state, reload } = useApiResource(load);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const [lifting, setLifting] = useState<string | null>(null);

  function unblock(blockId: string) {
    setFailure(null);
    setLifting(blockId);
    trustApi
      .unblock(blockId)
      .then(reload, (caught: unknown) => {
        setFailure(toApiFailure(caught));
      })
      .finally(() => {
        setLifting(null);
      });
  }

  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold text-teal-800">Blocked people</h1>
      <p className="mt-2 text-sm text-slate-600">
        You and someone you blocked don&apos;t see each other&apos;s trips and can&apos;t message each other.
      </p>
      <div className="mt-6 space-y-3">
        {state.kind === 'loading' && (
          <p role="status" className="text-slate-600">
            Loading…
          </p>
        )}
        {state.kind === 'failed' && (
          <>
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </>
        )}
        {state.kind === 'ready' && state.data.length === 0 && <p className="text-slate-600">You haven&apos;t blocked anyone.</p>}
        {state.kind === 'ready' && state.data.length > 0 && (
          <ul className="space-y-2">
            {state.data.map((block) => (
              <li
                key={block.id}
                className="flex items-center justify-between rounded-md border border-slate-200 bg-white px-4 py-3 text-sm"
              >
                <span>
                  <span className="font-medium">{block.firstName === '' ? 'Someone' : block.firstName}</span>
                  <span className="text-slate-600"> · since {formatDay(block.since)}</span>
                </span>
                <button
                  type="button"
                  onClick={() => {
                    unblock(block.id);
                  }}
                  disabled={lifting !== null}
                  className="font-medium text-teal-700 underline disabled:text-slate-400"
                >
                  {lifting === block.id ? 'Unblocking…' : 'Unblock'}
                </button>
              </li>
            ))}
          </ul>
        )}
        {failure !== null && <ErrorNotice message={failure.message} correlationId={failure.correlationId} />}
      </div>
    </section>
  );
}
