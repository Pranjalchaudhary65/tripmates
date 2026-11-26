import { useCallback, useState } from 'react';
import { DATA_REQUEST_STATUS_LABELS, DATA_REQUEST_TYPE_LABELS, formatWhen } from '../admin/adminLabels';
import { adminApi, type AdminDataRequest } from '../api/adminApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { Pager } from '../components/Pager';
import { useApiResource } from '../hooks/useApiResource';

/**
 * Data export and account deletion requests, newest first, by status only.
 * A moderator sees that a request exists and how far it got; never whose it
 * is beyond the request ID, and never an export's contents (the server has
 * no route that would return them to anyone but the requester).
 */
export function AdminDataRequestsPage() {
  const [page, setPage] = useState(0);
  const load = useCallback(() => adminApi.dataRequests(page), [page]);
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-4xl">
      <h1 className="text-2xl font-bold text-teal-800">Data requests</h1>
      <p className="mt-2 text-sm text-slate-600">
        Requests run on their own within a few minutes. A failed one stays here so it can be looked into; the
        audit log has each step.
      </p>

      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading data requests…
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
      {state.kind === 'ready' && state.data.items.length === 0 && (
        <p className="mt-6 text-slate-600">No one has asked for an export or a deletion yet.</p>
      )}
      {state.kind === 'ready' && state.data.items.length > 0 && (
        <>
          <ul className="mt-6 space-y-3">
            {state.data.items.map((request) => (
              <DataRequestRow key={request.id} request={request} />
            ))}
          </ul>
          <Pager label="Data request pages" page={state.data.page} totalPages={state.data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}

function DataRequestRow({ request }: { request: AdminDataRequest }) {
  const failed = request.status === 'FAILED';
  return (
    <li className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
      <p className="font-semibold text-slate-900">
        {DATA_REQUEST_TYPE_LABELS[request.type]}:{' '}
        <span className={failed ? 'text-red-800' : 'text-slate-700'}>{DATA_REQUEST_STATUS_LABELS[request.status]}</span>
      </p>
      <p className="text-xs text-slate-500">
        Asked {formatWhen(request.requestedAt)}
        {request.completedAt !== null && ` · finished ${formatWhen(request.completedAt)}`}
        {request.type === 'EXPORT' && request.expiresAt !== null && (request.downloadable
          ? ` · downloadable until ${formatWhen(request.expiresAt)}`
          : ' · no longer downloadable')}
      </p>
      <p className="mt-1 break-all font-mono text-xs text-slate-500">{request.id}</p>
    </li>
  );
}
