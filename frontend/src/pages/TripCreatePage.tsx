import { useState, type ChangeEvent, type SubmitEvent } from 'react';
import { useNavigate } from 'react-router';
import { toApiFailure } from '../api/problem';
import { tripsApi } from '../api/tripsApi';
import { BUDGET_BANDS, type BudgetBand } from '../api/vibeApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { PLACES, placeById } from '../trips/places';
import type { FieldErrors } from '../validation/identityRules';
import {
  addDays,
  DESCRIPTION_MAX_LENGTH,
  DESTINATION_MAX_LENGTH,
  MAX_SLOTS,
  TITLE_MAX_LENGTH,
  TRIP_FIELDS,
  tripToday,
  validateTrip,
  type TripField,
  type TripForm,
} from '../validation/tripRules';
import { BUDGET_LABELS } from '../vibe/vibeLabels';

/** The place picker's value for typing coordinates by hand. */
const ELSEWHERE = 'elsewhere';

/**
 * Opens a trip hosted by the caller. Every field is checked here for quick
 * feedback and again by the server, which also judges the dates by its own
 * clock; whatever it refuses comes back beside the field it belongs to.
 */
export function TripCreatePage() {
  const navigate = useNavigate();
  const today = tripToday();
  const [form, setForm] = useState<TripForm>({
    title: '',
    description: '',
    destinationName: '',
    latitude: '',
    longitude: '',
    radiusKm: '0',
    startDate: addDays(today, 7),
    endDate: addDays(today, 9),
    totalSlots: '2',
    budgetBand: 'MID',
  });
  const [placeId, setPlaceId] = useState('');
  const [clientErrors, setClientErrors] = useState<FieldErrors<TripField>>({});
  const server = useRequestFailure(TRIP_FIELDS);
  const { pending, run } = useSubmission();

  function update(field: Exclude<TripField, 'budgetBand'>, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    setClientErrors((current) => ({ ...current, [field]: undefined }));
    server.clearField(field);
  }

  function pickPlace(id: string) {
    setPlaceId(id);
    const place = placeById(id);
    if (place !== null) {
      setForm((current) => ({
        ...current,
        latitude: String(place.latitude),
        longitude: String(place.longitude),
        destinationName: current.destinationName.trim() === '' ? place.name : current.destinationName,
      }));
      setClientErrors((current) => ({ ...current, latitude: undefined, longitude: undefined, destinationName: undefined }));
    }
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    server.clear();
    const { errors, body } = validateTrip(form, tripToday());
    setClientErrors(errors);
    if (body === null) {
      return;
    }
    void run(async () => {
      try {
        const created = await tripsApi.create(body);
        await navigate(`/trips/${created.trip.id}`, { state: { justCreated: true } });
      } catch (error) {
        server.report(toApiFailure(error));
      }
    });
  }

  const errorFor = (field: TripField) => clientErrors[field] ?? server.fieldErrors[field];

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Host a trip</h1>
      <p className="mt-2 text-sm text-slate-600">
        Travellers ask to join and you decide who comes. Your surname, contact details and photo are never shown on the
        trip.
      </p>
      <form noValidate onSubmit={submit} className="mt-6 space-y-4">
        <FormField id="title" label="Title" error={errorFor('title')}>
          {(control) => (
            <input
              {...control}
              type="text"
              maxLength={TITLE_MAX_LENGTH}
              value={form.title}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                update('title', event.target.value);
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <FormField id="description" label="What the trip is like" error={errorFor('description')}>
          {(control) => (
            <textarea
              {...control}
              rows={5}
              maxLength={DESCRIPTION_MAX_LENGTH}
              value={form.description}
              onChange={(event: ChangeEvent<HTMLTextAreaElement>) => {
                update('description', event.target.value);
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <FormField id="place" label="Where on the map" hint="Pick the nearest town, or enter coordinates.">
          {(control) => (
            <select
              {...control}
              value={placeId}
              onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                pickPlace(event.target.value);
              }}
              className={inputClassName}
            >
              <option value="">Choose a place…</option>
              {PLACES.map((place) => (
                <option key={place.id} value={place.id}>
                  {place.name}
                </option>
              ))}
              <option value={ELSEWHERE}>Somewhere else (enter coordinates)</option>
            </select>
          )}
        </FormField>
        {(placeId === ELSEWHERE || errorFor('latitude') !== undefined || errorFor('longitude') !== undefined) && (
          <div className="grid grid-cols-2 gap-4">
            <FormField id="latitude" label="Latitude" error={errorFor('latitude')}>
              {(control) => (
                <input
                  {...control}
                  type="text"
                  inputMode="decimal"
                  value={form.latitude}
                  onChange={(event: ChangeEvent<HTMLInputElement>) => {
                    update('latitude', event.target.value);
                  }}
                  className={inputClassName}
                />
              )}
            </FormField>
            <FormField id="longitude" label="Longitude" error={errorFor('longitude')}>
              {(control) => (
                <input
                  {...control}
                  type="text"
                  inputMode="decimal"
                  value={form.longitude}
                  onChange={(event: ChangeEvent<HTMLInputElement>) => {
                    update('longitude', event.target.value);
                  }}
                  className={inputClassName}
                />
              )}
            </FormField>
          </div>
        )}
        <FormField id="destinationName" label="Destination name" error={errorFor('destinationName')}>
          {(control) => (
            <input
              {...control}
              type="text"
              maxLength={DESTINATION_MAX_LENGTH}
              value={form.destinationName}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                update('destinationName', event.target.value);
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <FormField
          id="radiusKm"
          label="How far around it you will roam (km)"
          hint="0 if you stay in one place."
          error={errorFor('radiusKm')}
        >
          {(control) => (
            <input
              {...control}
              type="text"
              inputMode="decimal"
              value={form.radiusKm}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                update('radiusKm', event.target.value);
              }}
              className={inputClassName}
            />
          )}
        </FormField>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField id="startDate" label="Start date" error={errorFor('startDate')}>
            {(control) => (
              <input
                {...control}
                type="date"
                min={today}
                value={form.startDate}
                onChange={(event: ChangeEvent<HTMLInputElement>) => {
                  update('startDate', event.target.value);
                }}
                className={inputClassName}
              />
            )}
          </FormField>
          <FormField id="endDate" label="End date" error={errorFor('endDate')}>
            {(control) => (
              <input
                {...control}
                type="date"
                min={form.startDate === '' ? today : form.startDate}
                value={form.endDate}
                onChange={(event: ChangeEvent<HTMLInputElement>) => {
                  update('endDate', event.target.value);
                }}
                className={inputClassName}
              />
            )}
          </FormField>
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField
            id="totalSlots"
            label="Companions wanted"
            hint={`Not counting you. 1 to ${String(MAX_SLOTS)}.`}
            error={errorFor('totalSlots')}
          >
            {(control) => (
              <input
                {...control}
                type="number"
                inputMode="numeric"
                min={1}
                max={MAX_SLOTS}
                value={form.totalSlots}
                onChange={(event: ChangeEvent<HTMLInputElement>) => {
                  update('totalSlots', event.target.value);
                }}
                className={inputClassName}
              />
            )}
          </FormField>
          <FormField id="budgetBand" label="Budget" error={errorFor('budgetBand')}>
            {(control) => (
              <select
                {...control}
                value={form.budgetBand}
                onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                  const band = BUDGET_BANDS.find((value: BudgetBand) => value === event.target.value);
                  if (band !== undefined) {
                    setForm((current) => ({ ...current, budgetBand: band }));
                    server.clearField('budgetBand');
                  }
                }}
                className={inputClassName}
              >
                {BUDGET_BANDS.map((band) => (
                  <option key={band} value={band}>
                    {BUDGET_LABELS[band]}
                  </option>
                ))}
              </select>
            )}
          </FormField>
        </div>
        {server.notice !== null && (
          <ErrorNotice
            message={server.notice.message}
            correlationId={server.notice.correlationId}
            retrySecondsLeft={server.noticeRetrySeconds}
          />
        )}
        <SubmitButton
          label="Open the trip"
          pendingLabel="Opening…"
          pending={pending}
          waitSeconds={server.retrySecondsLeft}
        />
      </form>
    </section>
  );
}
