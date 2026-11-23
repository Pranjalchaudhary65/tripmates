import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { isRecord } from '../api/accessGrant';
import { toApiFailure } from '../api/problem';
import {
  BUDGET_BANDS,
  EXPENSE_SPLIT_STYLES,
  GENDER_PREFERENCES,
  HABIT_PREFERENCES,
  PACES,
  PLANNING_STYLES,
  vibeApi,
  WAKE_TIMES,
  type DealBreakers,
  type VibeDimensions,
  type VibeProfileView,
} from '../api/vibeApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import {
  BUDGET_LABELS,
  EXPENSE_LABELS,
  GENDER_LABELS,
  HABIT_LABELS,
  PACE_LABELS,
  PLANNING_LABELS,
  SCALE_ENDS,
  WAKE_LABELS,
} from '../vibe/vibeLabels';

const loadProfile = () => vibeApi.profile();

const DIMENSION_FIELDS = [
  'budgetBand',
  'pace',
  'wakeTime',
  'planningStyle',
  'socialEnergy',
  'riskAppetite',
  'foodAdventurousness',
  'expenseSplitStyle',
] as const satisfies readonly (keyof VibeDimensions)[];

const DEAL_BREAKER_FIELDS = [
  'smoking',
  'drinking',
  'genderPreference',
  'minTrustScore',
] as const satisfies readonly (keyof DealBreakers)[];

const DEFAULT_DEAL_BREAKERS: DealBreakers = {
  smoking: 'EITHER',
  drinking: 'EITHER',
  genderPreference: 'ANY',
  minTrustScore: 0,
};

/**
 * The caller's vibe: what the quiz worked out, a way to correct it, and the
 * deal-breakers matching applies before any score. Loaded with one call;
 * every save answers with the whole view again.
 */
export function VibeProfilePage() {
  const { state, reload } = useApiResource(loadProfile);
  const [latest, setLatest] = useState<VibeProfileView | null>(null);
  const location = useLocation();
  const justFinished = isRecord(location.state) && location.state['justFinished'] === true;
  const view = latest ?? (state.kind === 'ready' ? state.data : null);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">My vibe</h1>
      {view === null && state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading your vibe…
        </p>
      )}
      {view === null && state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {view !== null && (
        <>
          {justFinished && view.profile !== null && (
            <p role="status" className="mt-4 rounded-md bg-teal-50 px-3 py-2 text-sm text-teal-900">
              Here is your travel vibe. Change anything that does not sound like you.
            </p>
          )}
          {view.profile === null ? (
            <div className="mt-6 rounded-md border border-slate-200 bg-white px-4 py-4">
              <p className="text-slate-700">You have not taken the vibe quiz yet.</p>
              <Link to="/vibe/quiz" className="mt-2 inline-block font-medium text-teal-700 underline">
                Take the quiz
              </Link>
            </div>
          ) : (
            <ProfileSection profile={view.profile} onSaved={setLatest} />
          )}
          <DealBreakersSection current={view.dealBreakers} onSaved={setLatest} />
        </>
      )}
    </section>
  );
}

function ProfileSection({
  profile,
  onSaved,
}: {
  profile: NonNullable<VibeProfileView['profile']>;
  onSaved: (view: VibeProfileView) => void;
}) {
  const [editing, setEditing] = useState(false);
  return (
    <section className="mt-6 rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="font-semibold text-slate-900">Travel style</h2>
      {editing ? (
        <AdjustForm
          profile={profile}
          onSaved={(view) => {
            onSaved(view);
            setEditing(false);
          }}
          onCancel={() => {
            setEditing(false);
          }}
        />
      ) : (
        <>
          <dl className="mt-3 grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
            <Row term="Budget" value={BUDGET_LABELS[profile.budgetBand]} />
            <Row term="Pace" value={PACE_LABELS[profile.pace]} />
            <Row term="Mornings" value={WAKE_LABELS[profile.wakeTime]} />
            <Row term="Planning" value={PLANNING_LABELS[profile.planningStyle]} />
            <Row term="Social energy" value={`${String(profile.socialEnergy)} of 5`} />
            <Row term="Appetite for risk" value={`${String(profile.riskAppetite)} of 5`} />
            <Row term="Food" value={`${String(profile.foodAdventurousness)} of 5`} />
            <Row term="Shared costs" value={EXPENSE_LABELS[profile.expenseSplitStyle]} />
          </dl>
          {profile.adjustedAt !== null && (
            <p className="mt-3 text-xs text-slate-500">You adjusted this by hand after the quiz.</p>
          )}
          <div className="mt-4 flex gap-4 text-sm">
            <button
              type="button"
              onClick={() => {
                setEditing(true);
              }}
              className="font-medium text-teal-700 underline"
            >
              Adjust
            </button>
            <Link to="/vibe/quiz" className="font-medium text-teal-700 underline">
              Retake the quiz
            </Link>
          </div>
        </>
      )}
    </section>
  );
}

function Row({ term, value }: { term: string; value: string }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-100 py-1">
      <dt className="text-slate-600">{term}</dt>
      <dd className="font-medium text-slate-900">{value}</dd>
    </div>
  );
}

function AdjustForm({
  profile,
  onSaved,
  onCancel,
}: {
  profile: VibeDimensions;
  onSaved: (view: VibeProfileView) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<VibeDimensions>({
    budgetBand: profile.budgetBand,
    pace: profile.pace,
    wakeTime: profile.wakeTime,
    planningStyle: profile.planningStyle,
    socialEnergy: profile.socialEnergy,
    riskAppetite: profile.riskAppetite,
    foodAdventurousness: profile.foodAdventurousness,
    expenseSplitStyle: profile.expenseSplitStyle,
  });
  const server = useRequestFailure(DIMENSION_FIELDS);
  const { pending, run } = useSubmission();

  function update<K extends keyof VibeDimensions>(field: K, value: VibeDimensions[K]) {
    setForm((current) => ({ ...current, [field]: value }));
    server.clearField(field);
  }

  function save(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    server.clear();
    const body = form;
    void run(async () => {
      try {
        onSaved(await vibeApi.adjust(body));
      } catch (error) {
        server.report(toApiFailure(error));
      }
    });
  }

  return (
    <form noValidate onSubmit={save} className="mt-3 space-y-4">
      <Choice
        id="budgetBand"
        label="Budget"
        values={BUDGET_BANDS}
        labels={BUDGET_LABELS}
        value={form.budgetBand}
        error={server.fieldErrors.budgetBand}
        onChange={(value) => {
          update('budgetBand', value);
        }}
      />
      <Choice
        id="pace"
        label="Pace"
        values={PACES}
        labels={PACE_LABELS}
        value={form.pace}
        error={server.fieldErrors.pace}
        onChange={(value) => {
          update('pace', value);
        }}
      />
      <Choice
        id="wakeTime"
        label="Mornings"
        values={WAKE_TIMES}
        labels={WAKE_LABELS}
        value={form.wakeTime}
        error={server.fieldErrors.wakeTime}
        onChange={(value) => {
          update('wakeTime', value);
        }}
      />
      <Choice
        id="planningStyle"
        label="Planning"
        values={PLANNING_STYLES}
        labels={PLANNING_LABELS}
        value={form.planningStyle}
        error={server.fieldErrors.planningStyle}
        onChange={(value) => {
          update('planningStyle', value);
        }}
      />
      <Scale
        id="socialEnergy"
        label="Social energy"
        ends={SCALE_ENDS.socialEnergy}
        value={form.socialEnergy}
        error={server.fieldErrors.socialEnergy}
        onChange={(value) => {
          update('socialEnergy', value);
        }}
      />
      <Scale
        id="riskAppetite"
        label="Appetite for risk"
        ends={SCALE_ENDS.riskAppetite}
        value={form.riskAppetite}
        error={server.fieldErrors.riskAppetite}
        onChange={(value) => {
          update('riskAppetite', value);
        }}
      />
      <Scale
        id="foodAdventurousness"
        label="Food"
        ends={SCALE_ENDS.foodAdventurousness}
        value={form.foodAdventurousness}
        error={server.fieldErrors.foodAdventurousness}
        onChange={(value) => {
          update('foodAdventurousness', value);
        }}
      />
      <Choice
        id="expenseSplitStyle"
        label="Shared costs"
        values={EXPENSE_SPLIT_STYLES}
        labels={EXPENSE_LABELS}
        value={form.expenseSplitStyle}
        error={server.fieldErrors.expenseSplitStyle}
        onChange={(value) => {
          update('expenseSplitStyle', value);
        }}
      />
      {server.notice !== null && (
        <ErrorNotice
          message={server.notice.message}
          correlationId={server.notice.correlationId}
          retrySecondsLeft={server.noticeRetrySeconds}
        />
      )}
      <div className="flex items-center gap-4">
        <div className="flex-1">
          <SubmitButton
            label="Save my vibe"
            pendingLabel="Saving…"
            pending={pending}
            waitSeconds={server.retrySecondsLeft}
          />
        </div>
        <button type="button" onClick={onCancel} className="font-medium text-slate-700 underline">
          Cancel
        </button>
      </div>
    </form>
  );
}

function DealBreakersSection({
  current,
  onSaved,
}: {
  current: VibeProfileView['dealBreakers'];
  onSaved: (view: VibeProfileView) => void;
}) {
  const [form, setForm] = useState<DealBreakers>(
    current === null
      ? DEFAULT_DEAL_BREAKERS
      : {
          smoking: current.smoking,
          drinking: current.drinking,
          genderPreference: current.genderPreference,
          minTrustScore: current.minTrustScore,
        },
  );
  const [scoreError, setScoreError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const server = useRequestFailure(DEAL_BREAKER_FIELDS);
  const { pending, run } = useSubmission();

  function update<K extends keyof DealBreakers>(field: K, value: DealBreakers[K]) {
    setForm((previous) => ({ ...previous, [field]: value }));
    setSaved(false);
    server.clearField(field);
  }

  function save(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    server.clear();
    setSaved(false);
    const score = form.minTrustScore;
    if (!Number.isInteger(score) || score < 0 || score > 100) {
      setScoreError('Enter a whole number from 0 to 100.');
      return;
    }
    setScoreError(null);
    const body = form;
    void run(async () => {
      try {
        onSaved(await vibeApi.setDealBreakers(body));
        setSaved(true);
      } catch (error) {
        server.report(toApiFailure(error));
      }
    });
  }

  return (
    <section className="mt-6 rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="font-semibold text-slate-900">Deal-breakers</h2>
      <p className="mt-1 text-sm text-slate-600">
        Hard limits. Trips and people that cross one are never suggested to you, whatever else matches.
      </p>
      <form noValidate onSubmit={save} className="mt-3 space-y-4">
        <Choice
          id="smoking"
          label="Smoking"
          values={HABIT_PREFERENCES}
          labels={HABIT_LABELS}
          value={form.smoking}
          error={server.fieldErrors.smoking}
          onChange={(value) => {
            update('smoking', value);
          }}
        />
        <Choice
          id="drinking"
          label="Drinking"
          values={HABIT_PREFERENCES}
          labels={HABIT_LABELS}
          value={form.drinking}
          error={server.fieldErrors.drinking}
          onChange={(value) => {
            update('drinking', value);
          }}
        />
        <Choice
          id="genderPreference"
          label="Travel with"
          values={GENDER_PREFERENCES}
          labels={GENDER_LABELS}
          value={form.genderPreference}
          error={server.fieldErrors.genderPreference}
          onChange={(value) => {
            update('genderPreference', value);
          }}
        />
        <FormField
          id="minTrustScore"
          label="Lowest trust score I will match with"
          hint="0 to 100. 0 means anyone."
          error={scoreError ?? server.fieldErrors.minTrustScore}
        >
          {(control) => (
            <input
              {...control}
              type="number"
              inputMode="numeric"
              min={0}
              max={100}
              value={Number.isNaN(form.minTrustScore) ? '' : form.minTrustScore}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setScoreError(null);
                update('minTrustScore', event.target.value === '' ? Number.NaN : Number(event.target.value));
              }}
              className={inputClassName}
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
        {saved && (
          <p role="status" className="text-sm text-teal-800">
            Deal-breakers saved.
          </p>
        )}
        <SubmitButton
          label="Save deal-breakers"
          pendingLabel="Saving…"
          pending={pending}
          waitSeconds={server.retrySecondsLeft}
        />
      </form>
    </section>
  );
}

function Choice<T extends string>({
  id,
  label,
  values,
  labels,
  value,
  error,
  onChange,
}: {
  id: string;
  label: string;
  values: readonly T[];
  labels: Record<T, string>;
  value: T;
  error: string | undefined;
  onChange: (value: T) => void;
}) {
  return (
    <FormField id={id} label={label} error={error}>
      {(control) => (
        <select
          {...control}
          value={value}
          onChange={(event: ChangeEvent<HTMLSelectElement>) => {
            const chosen = values.find((candidate) => candidate === event.target.value);
            if (chosen !== undefined) {
              onChange(chosen);
            }
          }}
          className={inputClassName}
        >
          {values.map((option) => (
            <option key={option} value={option}>
              {labels[option]}
            </option>
          ))}
        </select>
      )}
    </FormField>
  );
}

function Scale({
  id,
  label,
  ends,
  value,
  error,
  onChange,
}: {
  id: string;
  label: string;
  ends: readonly [string, string];
  value: number;
  error: string | undefined;
  onChange: (value: number) => void;
}) {
  return (
    <FormField
      id={id}
      label={`${label}: ${String(value)} of 5`}
      hint={`1 = ${ends[0]}, 5 = ${ends[1]}`}
      error={error}
    >
      {(control) => (
        <input
          {...control}
          type="range"
          min={1}
          max={5}
          step={1}
          value={value}
          onChange={(event: ChangeEvent<HTMLInputElement>) => {
            onChange(Number(event.target.value));
          }}
          className="mt-2 block w-full"
        />
      )}
    </FormField>
  );
}
