import { useCallback, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { formatWhen, REPORT_STATUS_LABELS } from '../admin/adminLabels';
import { adminApi, REPORT_STATUSES, type AdminReport, type ReportOutcome, type ReportStatus } from '../api/adminApi';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { Pager } from '../components/Pager';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';
import { REPORT_REASON_LABELS } from '../trust/trustLabels';

interface Closed {
  outcome: ReportOutcome;
  suspended: boolean;
}

/**
 * The report queue, oldest first in each state, so the longest wait is
 * looked at first. Details are the reporter's own words, shown only here.
 * Closing a report is final (the server refuses a second decision), so both
 * outcomes go through a confirm step, and suspending is a separate, explicit
 * choice inside "take action".
 */
export function AdminReportsPage() {
  const [params, setParams] = useSearchParams();
  const status: ReportStatus = REPORT_STATUSES.find((value) => value === params.get('status')) ?? 'OPEN';
  const [page, setPage] = useState(0);
  const load = useCallback(() => adminApi.reports(status, page), [status, page]);
  const { state, reload } = useApiResource(load);
  const [closed, setClosed] = useState<Record<string, Closed>>({});

  return (
    <section className="max-w-4xl">
      <h1 className="text-2xl font-bold text-teal-800">Reports</h1>
      <p className="mt-2 text-sm text-slate-600">
        What people reported about someone they met through a trip. The reporter is never told the outcome, and the
        reported person is never told who reported them.
      </p>

      <div role="group" aria-label="Report state" className="mt-4 flex gap-2">
        {REPORT_STATUSES.map((value) => (
          <button
            key={value}
            type="button"
            aria-pressed={value === status}
            onClick={() => {
              setPage(0);
              setParams(value === 'OPEN' ? {} : { status: value });
            }}
            className={`rounded-md border px-3 py-1.5 text-sm font-medium ${
              value === status ? 'border-teal-700 bg-teal-700 text-white' : 'border-slate-300 text-slate-700'
            }`}
          >
            {REPORT_STATUS_LABELS[value]}
          </button>
        ))}
      </div>

      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading reports…
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
        <p className="mt-6 text-slate-600">
          {status === 'OPEN' ? 'No reports are waiting.' : `No ${REPORT_STATUS_LABELS[status].toLowerCase()} reports.`}
        </p>
      )}
      {state.kind === 'ready' && state.data.items.length > 0 && (
        <>
          <ul className="mt-6 space-y-4">
            {state.data.items.map((report) => (
              <ReportCard
                key={report.id}
                report={report}
                closed={closed[report.id] ?? null}
                onClosed={(result) => {
                  setClosed((current) => ({ ...current, [report.id]: result }));
                }}
              />
            ))}
          </ul>
          <Pager label="Report pages" page={state.data.page} totalPages={state.data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}

function ReportCard({
  report,
  closed,
  onClosed,
}: {
  report: AdminReport;
  closed: Closed | null;
  onClosed: (result: Closed) => void;
}) {
  const [confirming, setConfirming] = useState<ReportOutcome | null>(null);
  const [suspend, setSuspend] = useState(false);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();
  const name = report.reported.firstName;
  const others = report.reported.reporters;

  function confirm(outcome: ReportOutcome) {
    const alsoSuspend = outcome === 'ACTIONED' && suspend && report.reported.active;
    setFailure(null);
    void run(async () => {
      try {
        await adminApi.decideReport(report.id, outcome, alsoSuspend);
        onClosed({ outcome, suspended: alsoSuspend });
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  return (
    <li className="rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="font-semibold text-slate-900">
        {REPORT_REASON_LABELS[report.reason]}: about {name}
      </h2>
      <p className="text-xs text-slate-500">
        Filed {formatWhen(report.createdAt)} by {report.reporter.firstName}
        {report.tripId !== null && ` · trip ${report.tripId}`}
      </p>
      <p className="mt-2 text-sm text-slate-700">
        {name}&apos;s account is {report.reported.active ? 'active' : 'not active (suspended, banned or deleted)'}.{' '}
        {reporterCount(others)}
      </p>

      <div className="mt-3 rounded-md bg-slate-50 px-3 py-2 text-sm">
        {report.details !== null ? (
          <blockquote className="whitespace-pre-wrap break-words text-slate-800">{report.details}</blockquote>
        ) : report.detailsUnreadable ? (
          <p className="text-slate-600">The details can no longer be read: the reporter deleted their account.</p>
        ) : (
          <p className="text-slate-600">No details given.</p>
        )}
      </div>

      <p className="mt-3 flex gap-4 text-sm">
        <Link
          to={`/admin/users?userId=${encodeURIComponent(report.reported.userId)}`}
          className="font-medium text-teal-700 underline"
        >
          {name}&apos;s account
        </Link>
        <Link
          to={`/admin/audit?entityId=${encodeURIComponent(report.reported.userId)}`}
          className="font-medium text-teal-700 underline"
        >
          Audit trail for {name}
        </Link>
      </p>

      {failure !== null && (
        <div className="mt-3">
          <ErrorNotice message={failure.message} correlationId={failure.correlationId} />
        </div>
      )}

      {closed !== null && (
        <p role="status" className="mt-3 font-medium text-slate-800">
          {closed.outcome === 'ACTIONED' ? 'Closed as actioned.' : 'Closed as dismissed.'}
          {closed.suspended && ` ${name}'s account is suspended and their sessions have ended.`}
        </p>
      )}

      {closed === null && report.status === 'OPEN' && confirming === null && (
        <div className="mt-3 flex gap-3">
          <button
            type="button"
            onClick={() => {
              setConfirming('ACTIONED');
            }}
            aria-label={`Take action on the report about ${name}`}
            className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800"
          >
            Take action
          </button>
          <button
            type="button"
            onClick={() => {
              setConfirming('DISMISSED');
            }}
            aria-label={`Dismiss the report about ${name}`}
            className="rounded-md border border-slate-300 px-4 py-2 font-semibold text-slate-800 hover:bg-slate-50"
          >
            Dismiss
          </button>
        </div>
      )}

      {closed === null && confirming !== null && (
        <div className="mt-3 space-y-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-3 text-sm">
          <p className="text-amber-900">
            {confirming === 'ACTIONED'
              ? 'Close this report as actioned? A closed report cannot be reopened.'
              : `Close this report as dismissed? It will no longer count towards ${name}'s automatic suspension, and a closed report cannot be reopened.`}
          </p>
          {confirming === 'ACTIONED' && report.reported.active && (
            <label className="flex items-start gap-2">
              <input
                type="checkbox"
                checked={suspend}
                onChange={(event) => {
                  setSuspend(event.target.checked);
                }}
                className="mt-1"
              />
              <span>
                Also suspend {name}. This ends every session they have and penalises everyone vouching for them. You
                can reinstate them from Accounts.
              </span>
            </label>
          )}
          <div className="flex gap-3">
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                confirm(confirming);
              }}
              className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800 disabled:bg-slate-400"
            >
              {pending ? 'Saving…' : confirming === 'ACTIONED' ? 'Confirm action' : 'Confirm dismissal'}
            </button>
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                setConfirming(null);
                setSuspend(false);
              }}
              className="font-medium text-slate-700 underline"
            >
              Back
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

function reporterCount(people: number): string {
  if (people === 0) {
    return 'No reports about them are counted now (dismissed reports are not).';
  }
  return people === 1
    ? 'Reported by 1 person (dismissed reports not counted).'
    : `Reported by ${String(people)} different people (dismissed reports not counted).`;
}
