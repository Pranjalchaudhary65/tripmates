import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { verificationApi, type GivenVouch, type VouchSummary } from '../api/verificationApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import type { FieldErrors } from '../validation/identityRules';
import { validateTripMatesId, type VouchField } from '../validation/verificationRules';

const VOUCH_FIELDS: readonly VouchField[] = ['vouchedUserId'];

const loadVouches = () => verificationApi.vouches();

const SINCE = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' });

/**
 * Vouching for people the user knows in person. A vouch puts a little of
 * the voucher's own standing behind someone, which is why it needs the
 * selfie tier and is capped. Friends are found by the TripMates ID they
 * share, never by email, so this screen cannot be used to test whether an
 * address has an account.
 */
export function VouchPage() {
  const { state, reload } = useApiResource(loadVouches);
  const [latest, setLatest] = useState<VouchSummary | null>(null);
  const summary = latest ?? (state.kind === 'ready' ? state.data : null);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Vouch for a friend</h1>
      <p className="mt-2 text-sm text-slate-600">
        Vouch only for people you have met and would travel with yourself. If someone you vouch for is suspended,
        your own trust score takes a small hit.
      </p>

      {summary === null && state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading your vouches…
        </p>
      )}
      {summary === null && state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {summary !== null && <Vouches summary={summary} onChanged={setLatest} />}
    </section>
  );
}

function Vouches({ summary, onChanged }: { summary: VouchSummary; onChanged: (summary: VouchSummary) => void }) {
  const full = summary.given.length >= summary.limit;
  return (
    <>
      <p className="mt-6 text-sm text-slate-700">
        You vouch for {summary.given.length} of {summary.limit} people. {summary.receivedCount} vouch for you.
      </p>
      {full ? (
        <p className="mt-4 text-sm text-slate-700">Withdraw a vouch to vouch for someone new.</p>
      ) : (
        <GiveVouch
          onGiven={(vouch) => {
            onChanged({ ...summary, given: [vouch, ...summary.given] });
          }}
        />
      )}
      {summary.given.length === 0 ? (
        <p className="mt-6 text-slate-600">You are not vouching for anyone yet.</p>
      ) : (
        <ul className="mt-6 divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
          {summary.given.map((vouch) => (
            <VouchRow
              key={vouch.id}
              vouch={vouch}
              onWithdrawn={() => {
                onChanged({ ...summary, given: summary.given.filter((other) => other.id !== vouch.id) });
              }}
            />
          ))}
        </ul>
      )}
    </>
  );
}

function GiveVouch({ onGiven }: { onGiven: (vouch: GivenVouch) => void }) {
  const [friendId, setFriendId] = useState('');
  const [errors, setErrors] = useState<FieldErrors<VouchField>>({});
  const server = useRequestFailure(VOUCH_FIELDS);
  const { pending, run } = useSubmission();

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const checked = validateTripMatesId(friendId);
    setErrors(checked.errors);
    server.clear();
    if (checked.userId === null) {
      return;
    }
    const userId = checked.userId;
    void run(async () => {
      try {
        onGiven(await verificationApi.vouch(userId));
        setFriendId('');
      } catch (error) {
        server.report(toApiFailure(error));
      }
    });
  }

  return (
    <form noValidate onSubmit={submit} className="mt-4 space-y-3">
      <FormField
        id="friend-id"
        label="Your friend's TripMates ID"
        hint="They can find it in their verification centre."
        error={errors.vouchedUserId ?? server.fieldErrors.vouchedUserId}
      >
        {(control) => (
          <input
            {...control}
            autoComplete="off"
            spellCheck={false}
            value={friendId}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setFriendId(event.target.value);
              setErrors({});
              server.clearField('vouchedUserId');
            }}
            className={`${inputClassName} font-mono`}
          />
        )}
      </FormField>
      {server.notice !== null && (
        <ErrorNotice
          message={server.notice.message}
          correlationId={server.notice.correlationId}
          retrySecondsLeft={server.noticeRetrySeconds}
        />
      )}
      <SubmitButton label="Vouch" pendingLabel="Vouching…" pending={pending} waitSeconds={server.retrySecondsLeft} />
    </form>
  );
}

function VouchRow({ vouch, onWithdrawn }: { vouch: GivenVouch; onWithdrawn: () => void }) {
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  function withdraw() {
    setFailure(null);
    void run(async () => {
      try {
        await verificationApi.withdrawVouch(vouch.id);
        onWithdrawn();
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  return (
    <li className="px-4 py-3">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="font-medium text-slate-900">{vouch.vouchedFirstName}</p>
          <p className="text-xs text-slate-500">Since {formatDate(vouch.since)}</p>
        </div>
        <button
          type="button"
          onClick={withdraw}
          disabled={pending}
          aria-label={`Withdraw your vouch for ${vouch.vouchedFirstName}`}
          className="rounded-md border border-slate-300 px-3 py-1 text-sm font-medium text-slate-800 hover:bg-slate-50 disabled:text-slate-400"
        >
          {pending ? 'Withdrawing…' : 'Withdraw'}
        </button>
      </div>
      {failure !== null && (
        <div className="mt-2">
          <ErrorNotice message={failure.message} correlationId={failure.correlationId} />
        </div>
      )}
    </li>
  );
}

function formatDate(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'an unknown date' : SINCE.format(at);
}
