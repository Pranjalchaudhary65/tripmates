import { useCallback, useState, type ChangeEvent, type SubmitEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { tripsApi, type TripSearch } from '../api/tripsApi';
import { BUDGET_BANDS, type BudgetBand } from '../api/vibeApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { Pager } from '../components/Pager';
import { TripCard } from '../components/TripCard';
import { useApiResource } from '../hooks/useApiResource';
import { PLACES, placeById, SEARCH_RADII_KM } from '../trips/places';
import { dateWindowError } from '../validation/tripRules';
import { BUDGET_LABELS } from '../vibe/vibeLabels';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const DEFAULT_RADIUS_KM = 100;
/** TripController's Paging.MAX_PAGE; anything past it is clamped by the server anyway. */
const MAX_PAGE = 1000;

/** The filters as the form holds them; the URL is the source of truth between visits. */
interface Filters {
  near: string;
  radiusKm: number;
  from: string;
  to: string;
  budgets: BudgetBand[];
}

/**
 * Open trips other people host, soonest first. The filters live in the URL,
 * so a search survives a reload and can be shared, and nothing is kept in
 * browser storage. Anything in the URL that is not a known place, radius,
 * date or band is ignored rather than sent.
 */
export function TripBrowsePage() {
  const [params, setParams] = useSearchParams();
  const applied = readFilters(params);
  const page = readPage(params.get('page'));
  const searchKey = params.toString();

  const load = useCallback(() => {
    const current = new URLSearchParams(searchKey);
    return tripsApi.browse(toSearch(readFilters(current), readPage(current.get('page'))));
  }, [searchKey]);
  const { state, reload } = useApiResource(load);

  function apply(filters: Filters) {
    setParams(toParams(filters, 0));
  }

  return (
    <section className="max-w-3xl">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <h1 className="text-2xl font-bold text-teal-800">Find a trip</h1>
        <p className="flex gap-4 text-sm">
          <Link to="/trips/new" className="font-medium text-teal-700 underline">
            Host a trip
          </Link>
          <Link to="/trips/mine" className="font-medium text-teal-700 underline">
            My trips
          </Link>
          <Link to="/trips/requests" className="font-medium text-teal-700 underline">
            Join requests
          </Link>
        </p>
      </div>

      <FilterForm key={searchKey} initial={applied} onApply={apply} />

      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Looking for trips…
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice
            message={state.failure.message}
            correlationId={state.failure.correlationId}
            retrySecondsLeft={state.failure.retryAfterSeconds}
          />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {state.kind === 'ready' && state.data.items.length === 0 && (
        <p className="mt-6 text-slate-600">
          No open trips match these filters. Try a wider area or other dates, or{' '}
          <Link to="/trips/new" className="font-medium text-teal-700 underline">
            host one yourself
          </Link>
          .
        </p>
      )}
      {state.kind === 'ready' && state.data.items.length > 0 && (
        <>
          <p className="mt-6 text-sm text-slate-600">
            {state.data.totalItems === 1 ? '1 trip' : `${String(state.data.totalItems)} trips`}
          </p>
          <ul className="mt-2 space-y-3">
            {state.data.items.map((trip) => (
              <TripCard key={trip.id} trip={trip} />
            ))}
          </ul>
          <Pager
            label="Trip pages"
            page={page}
            totalPages={state.data.totalPages}
            onChange={(next) => {
              setParams(toParams(applied, next));
            }}
          />
        </>
      )}
    </section>
  );
}

function FilterForm({ initial, onApply }: { initial: Filters; onApply: (filters: Filters) => void }) {
  const [form, setForm] = useState<Filters>(initial);
  const [dateError, setDateError] = useState<string | null>(null);

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const error = dateWindowError(form.from, form.to);
    setDateError(error);
    if (error === null) {
      onApply(form);
    }
  }

  function toggleBudget(band: BudgetBand, checked: boolean) {
    setForm((current) => ({
      ...current,
      budgets: checked ? [...current.budgets, band] : current.budgets.filter((value) => value !== band),
    }));
  }

  return (
    <form noValidate onSubmit={submit} aria-label="Trip filters" className="mt-4 rounded-md border border-slate-200 bg-white px-4 py-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField id="near" label="Near">
          {(control) => (
            <select
              {...control}
              value={form.near}
              onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                setForm((current) => ({ ...current, near: event.target.value }));
              }}
              className={inputClassName}
            >
              <option value="">Anywhere</option>
              {PLACES.map((place) => (
                <option key={place.id} value={place.id}>
                  {place.name}
                </option>
              ))}
            </select>
          )}
        </FormField>
        <FormField id="radiusKm" label="Within">
          {(control) => (
            <select
              {...control}
              disabled={form.near === ''}
              value={form.radiusKm}
              onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                setForm((current) => ({ ...current, radiusKm: Number(event.target.value) }));
              }}
              className={inputClassName}
            >
              {SEARCH_RADII_KM.map((radius) => (
                <option key={radius} value={radius}>
                  {radius} km
                </option>
              ))}
            </select>
          )}
        </FormField>
        <FormField id="from" label="Starting on or after">
          {(control) => (
            <input
              {...control}
              type="date"
              value={form.from}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setDateError(null);
                setForm((current) => ({ ...current, from: event.target.value }));
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <FormField id="to" label="Ending on or before" error={dateError ?? undefined}>
          {(control) => (
            <input
              {...control}
              type="date"
              value={form.to}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setDateError(null);
                setForm((current) => ({ ...current, to: event.target.value }));
              }}
              className={inputClassName}
            />
          )}
        </FormField>
      </div>
      <fieldset className="mt-4">
        <legend className="text-sm font-medium text-slate-800">Budget</legend>
        <div className="mt-1 flex flex-wrap gap-4 text-sm">
          {BUDGET_BANDS.map((band) => (
            <label key={band} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={form.budgets.includes(band)}
                onChange={(event: ChangeEvent<HTMLInputElement>) => {
                  toggleBudget(band, event.target.checked);
                }}
              />
              {BUDGET_LABELS[band]}
            </label>
          ))}
        </div>
      </fieldset>
      <button
        type="submit"
        className="mt-4 rounded-md bg-teal-700 px-4 py-2 font-semibold text-white shadow hover:bg-teal-800"
      >
        Search
      </button>
    </form>
  );
}

function readFilters(params: URLSearchParams): Filters {
  const near = placeById(params.get('near') ?? '')?.id ?? '';
  const radius = Number(params.get('radius'));
  const from = params.get('from') ?? '';
  const to = params.get('to') ?? '';
  const budgets = BUDGET_BANDS.filter((band) => params.getAll('budget').includes(band));
  const validFrom = ISO_DATE.test(from) ? from : '';
  const validTo = ISO_DATE.test(to) ? to : '';
  return {
    near,
    radiusKm: SEARCH_RADII_KM.includes(radius) ? radius : DEFAULT_RADIUS_KM,
    from: validFrom,
    to: dateWindowError(validFrom, validTo) === null ? validTo : '',
    budgets,
  };
}

function readPage(raw: string | null): number {
  const page = Number(raw);
  return Number.isInteger(page) && page >= 0 ? Math.min(page, MAX_PAGE) : 0;
}

function toParams(filters: Filters, page: number): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.near !== '') {
    params.set('near', filters.near);
    params.set('radius', String(filters.radiusKm));
  }
  if (filters.from !== '') {
    params.set('from', filters.from);
  }
  if (filters.to !== '') {
    params.set('to', filters.to);
  }
  for (const band of filters.budgets) {
    params.append('budget', band);
  }
  if (page > 0) {
    params.set('page', String(page));
  }
  return params;
}

function toSearch(filters: Filters, page: number): TripSearch {
  const place = placeById(filters.near);
  return {
    near: place === null ? null : { latitude: place.latitude, longitude: place.longitude, radiusKm: filters.radiusKm },
    from: filters.from === '' ? null : filters.from,
    to: filters.to === '' ? null : filters.to,
    budgets: filters.budgets,
    page,
  };
}
