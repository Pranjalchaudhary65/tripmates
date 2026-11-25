import { useCallback, useState, type SubmitEvent } from 'react';
import { toApiFailure } from '../api/problem';
import { settlementApi } from '../api/settlementApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { validateUpiId } from '../validation/settlementRules';

const FIELDS = ['upiId'] as const;

/**
 * The UPI ID people who owe the user money are sent to pay. Optional: without
 * one, they are told to ask in chat. TripMates never receives the payment.
 */
export function UpiIdPage() {
  const load = useCallback(() => settlementApi.upiId(), []);
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold text-teal-800">Your UPI ID</h1>
      <p className="mt-2 text-sm text-slate-600">
        When someone on your trip owes you money, their settle-up screen gets a button that opens their UPI app with
        your ID and the amount filled in. Only people who owe you see it. The money goes straight to you; TripMates never
        holds it.
      </p>
      <div className="mt-6">
        {state.kind === 'loading' && (
          <p role="status" className="text-slate-600">
            Loading…
          </p>
        )}
        {state.kind === 'failed' && (
          <div className="space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        )}
        {state.kind === 'ready' && <UpiEditor key={state.data ?? 'none'} current={state.data} onSaved={reload} />}
      </div>
    </section>
  );
}

function UpiEditor({ current, onSaved }: { current: string | null; onSaved: () => void }) {
  const [value, setValue] = useState(current ?? '');
  const [localError, setLocalError] = useState<string | undefined>(undefined);
  const failure = useRequestFailure(FIELDS);
  const { pending, run } = useSubmission();
  const [removing, setRemoving] = useState(false);

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const error = validateUpiId(value);
    setLocalError(error);
    failure.clear();
    if (error !== undefined) {
      return;
    }
    void run(async () => {
      try {
        await settlementApi.setUpiId(value.trim().toLowerCase());
        onSaved();
      } catch (caught) {
        failure.report(toApiFailure(caught));
      }
    });
  }

  function remove() {
    setRemoving(true);
    settlementApi
      .removeUpiId()
      .then(onSaved, (caught: unknown) => {
        failure.report(toApiFailure(caught));
      })
      .finally(() => {
        setRemoving(false);
      });
  }

  return (
    <div className="space-y-6">
      {current !== null && (
        <div role="region" aria-label="Current UPI ID" className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
          <p className="font-medium text-slate-900">{current}</p>
          <button
            type="button"
            onClick={remove}
            disabled={removing}
            className="mt-2 text-sm font-medium text-red-700 underline disabled:text-slate-400"
          >
            {removing ? 'Removing…' : 'Remove'}
          </button>
        </div>
      )}
      <form onSubmit={submit} noValidate className="space-y-4">
        <FormField
          id="upi-id"
          label={current === null ? 'Add a UPI ID' : 'Replace it'}
          error={localError ?? failure.fieldErrors.upiId}
          hint="Like name@okbank. People paying you will see a warning for two days after you change it."
        >
          {(control) => (
            <input
              {...control}
              value={value}
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              maxLength={100}
              onChange={(event) => {
                setValue(event.target.value);
                failure.clearField('upiId');
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        {failure.notice !== null && (
          <ErrorNotice
            message={failure.notice.message}
            correlationId={failure.notice.correlationId}
            retrySecondsLeft={failure.noticeRetrySeconds}
          />
        )}
        <SubmitButton label="Save UPI ID" pendingLabel="Saving…" pending={pending} />
      </form>
    </div>
  );
}
