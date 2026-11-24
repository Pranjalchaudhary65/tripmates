import type { NewTrip } from '../api/tripsApi';
import type { BudgetBand } from '../api/vibeApi';
import { LEGAL_TIME_ZONE, todayIn, type FieldErrors } from './identityRules';

/**
 * Client-side copies of the trip rules (CreateTripRequest, TripService,
 * TripController), for instant feedback only. The server checks every value
 * again and stays the authority on dates, which it judges by its own clock.
 */

export const TITLE_MIN_LENGTH = 3;
export const TITLE_MAX_LENGTH = 140;
export const DESCRIPTION_MAX_LENGTH = 4000;
export const DESTINATION_MAX_LENGTH = 120;
export const MAX_SLOTS = 20;
export const MAX_TRIP_RADIUS_KM = 500;
export const MAX_DAYS_AHEAD = 365;
export const MAX_LENGTH_DAYS = 60;
/** The widest browse circle the server accepts. */
export const MAX_SEARCH_RADIUS_KM = 1000;

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const RADIUS = /^\d{1,3}(\.\d{1,2})?$/;

export const TRIP_FIELDS = [
  'title',
  'description',
  'destinationName',
  'latitude',
  'longitude',
  'radiusKm',
  'startDate',
  'endDate',
  'totalSlots',
  'budgetBand',
] as const;

export type TripField = (typeof TRIP_FIELDS)[number];

/** The create form as typed: every input is text until it is checked. */
export interface TripForm {
  title: string;
  description: string;
  destinationName: string;
  latitude: string;
  longitude: string;
  radiusKm: string;
  startDate: string;
  endDate: string;
  totalSlots: string;
  budgetBand: BudgetBand;
}

/** Today in India, where the server judges every trip date. */
export function tripToday(now: Date = new Date()): string {
  return todayIn(LEGAL_TIME_ZONE, now);
}

export function addDays(isoDate: string, days: number): string {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

export function validateTrip(form: TripForm, today: string): { errors: FieldErrors<TripField>; body: NewTrip | null } {
  const errors: FieldErrors<TripField> = {};
  const title = form.title.trim();
  const description = form.description.trim();
  const destinationName = form.destinationName.trim();

  if (title.length < TITLE_MIN_LENGTH || title.length > TITLE_MAX_LENGTH) {
    errors.title = `Give the trip a title of ${String(TITLE_MIN_LENGTH)} to ${String(TITLE_MAX_LENGTH)} characters.`;
  } else if (hasForbiddenCharacter(title, false)) {
    errors.title = 'The title cannot contain control characters.';
  }
  if (description === '') {
    errors.description = 'Say what the trip is about.';
  } else if (description.length > DESCRIPTION_MAX_LENGTH) {
    errors.description = `Keep the description under ${String(DESCRIPTION_MAX_LENGTH)} characters.`;
  } else if (hasForbiddenCharacter(description, true)) {
    errors.description = 'The description cannot contain control characters.';
  }
  if (destinationName === '' || destinationName.length > DESTINATION_MAX_LENGTH) {
    errors.destinationName = `Name the destination in up to ${String(DESTINATION_MAX_LENGTH)} characters.`;
  } else if (hasForbiddenCharacter(destinationName, false)) {
    errors.destinationName = 'The destination cannot contain control characters.';
  }

  const latitude = readNumber(form.latitude);
  const longitude = readNumber(form.longitude);
  if (latitude === null || latitude < -90 || latitude > 90) {
    errors.latitude = 'Pick a place, or enter a latitude from -90 to 90.';
  }
  if (longitude === null || longitude < -180 || longitude > 180) {
    errors.longitude = 'Pick a place, or enter a longitude from -180 to 180.';
  }

  const radiusText = form.radiusKm.trim() === '' ? '0' : form.radiusKm.trim();
  const radiusKm = RADIUS.test(radiusText) ? Number(radiusText) : null;
  if (radiusKm === null || radiusKm > MAX_TRIP_RADIUS_KM) {
    errors.radiusKm = `Enter 0 to ${String(MAX_TRIP_RADIUS_KM)} km, with at most two decimals.`;
  }

  const dateError = tripDatesError(form.startDate, form.endDate, today);
  if (dateError !== null) {
    errors[dateError.field] = dateError.message;
  }

  const totalSlots = /^\d{1,2}$/.test(form.totalSlots.trim()) ? Number(form.totalSlots.trim()) : null;
  if (totalSlots === null || totalSlots < 1 || totalSlots > MAX_SLOTS) {
    errors.totalSlots = `Ask for 1 to ${String(MAX_SLOTS)} companions.`;
  }

  if (
    Object.keys(errors).length > 0 ||
    latitude === null ||
    longitude === null ||
    radiusKm === null ||
    totalSlots === null
  ) {
    return { errors, body: null };
  }
  return {
    errors,
    body: {
      title,
      description,
      destinationName,
      latitude,
      longitude,
      radiusKm,
      startDate: form.startDate,
      endDate: form.endDate,
      totalSlots,
      budgetBand: form.budgetBand,
    },
  };
}

/** TripService.requireValidDates: from today, at most a year ahead, at most 60 days long. */
function tripDatesError(
  startDate: string,
  endDate: string,
  today: string,
): { field: 'startDate' | 'endDate'; message: string } | null {
  if (!ISO_DATE.test(startDate)) {
    return { field: 'startDate', message: 'Pick a start date.' };
  }
  if (startDate < today || startDate > addDays(today, MAX_DAYS_AHEAD)) {
    return { field: 'startDate', message: 'Pick a start date from today up to a year ahead.' };
  }
  if (!ISO_DATE.test(endDate)) {
    return { field: 'endDate', message: 'Pick an end date.' };
  }
  if (endDate < startDate) {
    return { field: 'endDate', message: 'The trip cannot end before it starts.' };
  }
  if (daysBetween(startDate, endDate) > MAX_LENGTH_DAYS) {
    return { field: 'endDate', message: `Keep the trip to ${String(MAX_LENGTH_DAYS)} days after the start.` };
  }
  return null;
}

/**
 * What the server's \p{Cntrl} refuses (U+0000-U+001F and U+007F) and the
 * marks that reverse text direction; line breaks and tabs only where they
 * are allowed.
 */
export function hasForbiddenCharacter(text: string, allowLineBreaks: boolean): boolean {
  for (const character of text) {
    const code = character.codePointAt(0) ?? 0;
    const lineBreak = code === 0x09 || code === 0x0a || code === 0x0d;
    const control = code <= 0x1f || code === 0x7f;
    const bidi = (code >= 0x202a && code <= 0x202e) || (code >= 0x2066 && code <= 0x2069);
    if (bidi || (control && !(allowLineBreaks && lineBreak))) {
      return true;
    }
  }
  return false;
}

/** A finite decimal number, or null. Number('') is 0 and Number('1e3') is 1000; neither is accepted. */
function readNumber(raw: string): number | null {
  const text = raw.trim();
  if (!/^-?\d{1,3}(\.\d+)?$/.test(text)) {
    return null;
  }
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
}

/** The browse date window: both optional, never backwards. */
export function dateWindowError(from: string, to: string): string | null {
  if (from !== '' && to !== '' && to < from) {
    return 'The end of the date range cannot be before its start.';
  }
  return null;
}
