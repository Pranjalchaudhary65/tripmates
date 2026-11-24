import { useCallback, useState, type SubmitEvent } from 'react';
import { meetupApi, RELATIONSHIPS, type EmergencyContact, type Relationship } from '../api/meetupApi';
import { toApiFailure } from '../api/problem';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { RELATIONSHIP_LABELS } from '../meetup/meetupLabels';
import { validateEmergencyContact } from '../validation/meetupRules';

const FIELDS = ['name', 'relationship', 'phoneNumber'] as const;

/**
 * The one person TripMates alerts if the user goes quiet at a meet-up.
 * Needed before arranging one. The stored number only ever comes back
 * masked; to change it, the user types a new one.
 */
export function EmergencyContactPage() {
  const load = useCallback(() => meetupApi.emergencyContact(), []);
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold text-teal-800">Emergency contact</h1>
      <p className="mt-2 text-sm text-slate-600">
        Someone we can alert if you go quiet at a meet-up: 60 minutes after it starts, if you have neither checked in
        nor told us you are safe. They are never contacted otherwise, and the other traveller never sees them.
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
        {state.kind === 'ready' && <ContactEditor key={state.data?.updatedAt ?? 'none'} current={state.data} onSaved={reload} />}
      </div>
    </section>
  );
}

function ContactEditor({ current, onSaved }: { current: EmergencyContact | null; onSaved: () => void }) {
  const [name, setName] = useState(current?.name ?? '');
  const [relationship, setRelationship] = useState<Relationship | ''>(current?.relationship ?? '');
  const [phone, setPhone] = useState('');
  const [localErrors, setLocalErrors] = useState<Partial<Record<(typeof FIELDS)[number], string>>>({});
  const failure = useRequestFailure(FIELDS);
  const { pending, run } = useSubmission();
  const [removing, setRemoving] = useState(false);

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const errors = validateEmergencyContact({ name, relationship, phoneNumber: phone });
    setLocalErrors(errors);
    failure.clear();
    if (Object.keys(errors).length > 0 || relationship === '') {
      return;
    }
    void run(async () => {
      try {
        await meetupApi.setEmergencyContact({ name: name.trim(), relationship, phoneNumber: phone.trim() });
        onSaved();
      } catch (error) {
        failure.report(toApiFailure(error));
      }
    });
  }

  function remove() {
    setRemoving(true);
    meetupApi
      .removeEmergencyContact()
      .then(onSaved, (error: unknown) => {
        failure.report(toApiFailure(error));
      })
      .finally(() => {
        setRemoving(false);
      });
  }

  const errorFor = (field: (typeof FIELDS)[number]) => localErrors[field] ?? failure.fieldErrors[field];

  return (
    <div className="space-y-6">
      {current !== null && (
        <div role="region" aria-label="Current contact" className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
          <p className="font-medium text-slate-900">
            {current.name} · {RELATIONSHIP_LABELS[current.relationship]}
          </p>
          <p className="text-slate-600">{current.maskedPhone ?? 'Number no longer readable. Enter it again.'}</p>
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
        <h2 className="text-lg font-semibold text-slate-900">{current === null ? 'Add a contact' : 'Replace the contact'}</h2>
        <FormField id="contact-name" label="Their name" error={errorFor('name')}>
          {(control) => (
            <input
              {...control}
              value={name}
              maxLength={80}
              autoComplete="off"
              onChange={(event) => {
                setName(event.target.value);
                failure.clearField('name');
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <FormField id="contact-relationship" label="Who they are to you" error={errorFor('relationship')}>
          {(control) => (
            <select
              {...control}
              value={relationship}
              onChange={(event) => {
                setRelationship(RELATIONSHIPS.find((value) => value === event.target.value) ?? '');
              }}
              className={inputClassName}
            >
              <option value="">Choose…</option>
              {RELATIONSHIPS.map((value) => (
                <option key={value} value={value}>
                  {RELATIONSHIP_LABELS[value]}
                </option>
              ))}
            </select>
          )}
        </FormField>
        <FormField
          id="contact-phone"
          label="Their mobile number"
          error={errorFor('phoneNumber')}
          hint="With its country code, such as +91 98765 43210. Not your own number."
        >
          {(control) => (
            <input
              {...control}
              value={phone}
              type="tel"
              inputMode="tel"
              autoComplete="off"
              onChange={(event) => {
                setPhone(event.target.value);
                failure.clearField('phoneNumber');
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
        <SubmitButton label="Save contact" pendingLabel="Saving…" pending={pending} />
      </form>
    </div>
  );
}
