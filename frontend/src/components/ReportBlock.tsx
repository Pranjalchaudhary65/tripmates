import { useState, type SubmitEvent } from 'react';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { REPORT_REASONS, trustApi, type PersonTarget, type ReportReason } from '../api/trustApi';
import { useSubmission } from '../hooks/useSubmission';
import { REPORT_REASON_LABELS } from '../trust/trustLabels';
import { ErrorNotice } from './ErrorNotice';

const MAX_DETAILS = 1000;

type Mode = 'closed' | 'menu' | 'confirmBlock' | 'report' | 'blocked' | 'reported';

/**
 * Report or block one person the user met through a trip. Folded away until
 * asked for, so it never sits in the way, and every action needs one more
 * deliberate step. Nothing is said about what happens to the other person.
 */
export function ReportBlock({ target, firstName }: { target: PersonTarget; firstName: string }) {
  const [mode, setMode] = useState<Mode>('closed');
  const [reason, setReason] = useState<ReportReason | ''>('');
  const [details, setDetails] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  function block() {
    setFailure(null);
    void run(async () => {
      try {
        await trustApi.block(target);
        setMode('blocked');
      } catch (caught) {
        setFailure(toApiFailure(caught));
      }
    });
  }

  function report(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    if (reason === '') {
      setLocalError('Choose a reason.');
      return;
    }
    if (details.trim().length > MAX_DETAILS) {
      setLocalError(`Keep it to ${String(MAX_DETAILS)} characters.`);
      return;
    }
    setLocalError(null);
    const chosen = reason;
    void run(async () => {
      try {
        await trustApi.report(target, chosen, details);
        setMode('reported');
      } catch (caught) {
        setFailure(toApiFailure(caught));
      }
    });
  }

  const name = firstName === '' ? 'this person' : firstName;
  return (
    <div className="text-sm">
      {mode === 'closed' && (
        <button type="button" onClick={() => { setMode('menu'); }} className="text-slate-600 underline">
          Report or block {name}
        </button>
      )}
      {mode === 'menu' && (
        <div className="flex flex-wrap gap-3">
          <button type="button" onClick={() => { setMode('report'); }} className="font-medium text-red-700 underline">
            Report {name}
          </button>
          <button type="button" onClick={() => { setMode('confirmBlock'); }} className="font-medium text-red-700 underline">
            Block {name}
          </button>
          <button type="button" onClick={() => { setMode('closed'); }} className="text-slate-600 underline">
            Cancel
          </button>
        </div>
      )}
      {mode === 'confirmBlock' && (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-3 text-red-900">
          <p>You won&apos;t see each other&apos;s trips, and neither of you can message the other. {name} is not told.</p>
          <div className="mt-2 flex gap-3">
            <button
              type="button"
              onClick={block}
              disabled={pending}
              className="rounded bg-red-700 px-3 py-1 font-medium text-white disabled:bg-slate-400"
            >
              {pending ? 'Blocking…' : `Block ${name}`}
            </button>
            <button type="button" onClick={() => { setMode('menu'); }} className="underline">
              Back
            </button>
          </div>
        </div>
      )}
      {mode === 'report' && (
        <form onSubmit={report} noValidate className="space-y-2 rounded-md border border-slate-200 bg-white px-3 py-3">
          <label htmlFor="report-reason" className="block font-medium">
            What happened?
          </label>
          <select
            id="report-reason"
            value={reason}
            onChange={(event) => {
              setReason(REPORT_REASONS.find((value) => value === event.target.value) ?? '');
            }}
            className="w-full rounded border border-slate-300 px-2 py-1"
          >
            <option value="">Choose a reason</option>
            {REPORT_REASONS.map((value) => (
              <option key={value} value={value}>
                {REPORT_REASON_LABELS[value]}
              </option>
            ))}
          </select>
          <label htmlFor="report-details" className="block font-medium">
            Anything a moderator should know (optional)
          </label>
          <textarea
            id="report-details"
            value={details}
            maxLength={MAX_DETAILS}
            rows={3}
            onChange={(event) => {
              setDetails(event.target.value);
            }}
            className="w-full rounded border border-slate-300 px-2 py-1"
          />
          {localError !== null && (
            <p role="alert" className="text-red-700">
              {localError}
            </p>
          )}
          <p className="text-xs text-slate-600">
            Only a moderator reads this. {name} is never told who reported them. If you are in danger, call 112.
          </p>
          <div className="flex gap-3">
            <button
              type="submit"
              disabled={pending}
              className="rounded bg-red-700 px-3 py-1 font-medium text-white disabled:bg-slate-400"
            >
              {pending ? 'Sending…' : 'Send report'}
            </button>
            <button type="button" onClick={() => { setMode('menu'); }} className="underline">
              Back
            </button>
          </div>
        </form>
      )}
      {mode === 'blocked' && (
        <p role="status" className="text-slate-700">
          {name} is blocked. You can undo this from your trust score page.
        </p>
      )}
      {mode === 'reported' && (
        <p role="status" className="text-slate-700">
          Thanks. A moderator will look at it. You can also block {name}.
        </p>
      )}
      {failure !== null && (
        <div className="mt-2">
          <ErrorNotice message={failure.message} correlationId={failure.correlationId} retrySecondsLeft={failure.retryAfterSeconds} />
        </div>
      )}
    </div>
  );
}
