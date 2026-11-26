import { useCallback, useState, type ChangeEvent, type SubmitEvent } from 'react';
import { useSearchParams } from 'react-router';
import { auditEventLabel, formatWhen, UUID_PATTERN } from '../admin/adminLabels';
import { adminApi, type AuditEntry, type IntegrityCheck } from '../api/adminApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { Pager } from '../components/Pager';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';

const ID_HINT = 'Enter an ID, like 0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c.';

/**
 * The audit log, newest first, optionally only the rows about one ID (as
 * the thing acted on or the moderator who acted). Rows hold IDs, codes and
 * numbers only, so nothing here names a person. The integrity panel shows
 * the latest check of the hash chain and can run one now; a break is shown
 * as the first row whose hash no longer matches.
 */
export function AdminAuditPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('entityId');
  const filterValid = requested !== null && UUID_PATTERN.test(requested);
  const [input, setInput] = useState(requested ?? '');
  const [clientError, setClientError] = useState<string | null>(requested !== null && !filterValid ? ID_HINT : null);

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const id = input.trim();
    if (id === '') {
      setClientError(null);
      setParams({});
      return;
    }
    if (!UUID_PATTERN.test(id)) {
      setClientError(ID_HINT);
      return;
    }
    setClientError(null);
    setParams({ entityId: id.toLowerCase() });
  }

  return (
    <section className="max-w-4xl">
      <h1 className="text-2xl font-bold text-teal-800">Audit log</h1>
      <p className="mt-2 text-sm text-slate-600">
        Every moderation, safety, payment, consent and data action, in the order it happened. Rows can never be
        changed or deleted, and each one is chained to the one before it by its hash.
      </p>

      <form noValidate onSubmit={handleSubmit} className="mt-4 flex items-end gap-3">
        <div className="flex-1">
          <FormField id="audit-entity-id" label="Only rows about this ID (account, report, trip…)" error={clientError ?? undefined}>
            {(control) => (
              <input
                {...control}
                type="text"
                name="entityId"
                spellCheck={false}
                maxLength={36}
                value={input}
                onChange={(event: ChangeEvent<HTMLInputElement>) => {
                  setInput(event.target.value);
                  setClientError(null);
                }}
                className={inputClassName}
              />
            )}
          </FormField>
        </div>
        <button type="submit" className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800">
          Filter
        </button>
      </form>

      {requested === null || filterValid ? (
        <AuditEntries key={requested ?? 'all'} entityId={requested} />
      ) : (
        <p className="mt-6 text-slate-600">Fix the ID above to see its rows.</p>
      )}
    </section>
  );
}

function AuditEntries({ entityId }: { entityId: string | null }) {
  const [page, setPage] = useState(0);
  const load = useCallback(() => adminApi.auditLog(entityId, page), [entityId, page]);
  const { state, reload } = useApiResource(load);
  const [fresh, setFresh] = useState<IntegrityCheck | null>(null);

  if (state.kind === 'loading') {
    return (
      <p role="status" className="mt-6 text-slate-600">
        Loading the audit log…
      </p>
    );
  }
  if (state.kind === 'failed') {
    return (
      <div className="mt-6 space-y-3">
        <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
        <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
          Try again
        </button>
      </div>
    );
  }
  const { entries, latestCheck } = state.data;
  return (
    <>
      <IntegrityPanel latest={fresh ?? latestCheck} onChecked={setFresh} />
      {entries.items.length === 0 ? (
        <p className="mt-6 text-slate-600">{entityId === null ? 'Nothing has been recorded yet.' : 'No rows about this ID.'}</p>
      ) : (
        <>
          <ol className="mt-6 space-y-3">
            {entries.items.map((entry) => (
              <AuditRow key={entry.sequenceNo} entry={entry} />
            ))}
          </ol>
          <Pager label="Audit log pages" page={entries.page} totalPages={entries.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}

function IntegrityPanel({
  latest,
  onChecked,
}: {
  latest: IntegrityCheck | null;
  onChecked: (check: IntegrityCheck) => void;
}) {
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  function checkNow() {
    setFailure(null);
    void run(async () => {
      try {
        onChecked(await adminApi.checkAuditChain());
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  return (
    <div className="mt-6 space-y-3 rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
      <h2 className="font-semibold text-slate-900">Integrity</h2>
      {latest === null ? (
        <p className="text-slate-700">The chain has not been checked yet.</p>
      ) : latest.intact ? (
        <p className="text-slate-700">
          Last checked {formatWhen(latest.checkedAt)}: all {String(latest.rowsChecked)} rows intact.
        </p>
      ) : (
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 font-medium text-red-800">
          Last checked {formatWhen(latest.checkedAt)}: the chain is broken from row #{String(latest.firstBrokenSeq)}. That
          row or one before it was changed outside the application.
        </p>
      )}
      {failure !== null && <ErrorNotice message={failure.message} correlationId={failure.correlationId} />}
      <button
        type="button"
        disabled={pending}
        onClick={checkNow}
        className="rounded-md border border-teal-700 px-3 py-1.5 font-medium text-teal-800 hover:bg-teal-50 disabled:text-slate-400"
      >
        {pending ? 'Checking every row…' : 'Check the chain now'}
      </button>
    </div>
  );
}

function AuditRow({ entry }: { entry: AuditEntry }) {
  const metadata = Object.entries(entry.metadata);
  return (
    <li className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
      <p className="font-semibold text-slate-900">
        #{String(entry.sequenceNo)} {auditEventLabel(entry.eventType)}
      </p>
      <p className="text-xs text-slate-500">
        {formatWhen(entry.createdAt)} · by {entry.actorUserId ?? 'the system'}
      </p>
      <p className="mt-1 break-all font-mono text-xs text-slate-700">
        {entry.entityType} {entry.entityId}
      </p>
      {metadata.length > 0 && (
        <dl className="mt-2 grid grid-cols-[max-content_1fr] gap-x-4 text-xs">
          {metadata.map(([key, value]) => (
            <div key={key} className="contents">
              <dt className="text-slate-500">{key}</dt>
              <dd className="break-all font-mono text-slate-800">{String(value)}</dd>
            </div>
          ))}
        </dl>
      )}
      <p className="mt-2 font-mono text-xs text-slate-400" title={entry.rowHash}>
        hash {entry.rowHash.slice(0, 16)}…
      </p>
    </li>
  );
}
