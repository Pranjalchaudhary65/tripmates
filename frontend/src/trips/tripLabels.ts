import type { VerificationTier } from '../api/accessGrant';
import type { MatchDimension, MemberStatus, TripStatus } from '../api/tripsApi';

/** Words for trip and request codes; the server only ever sends the codes. */

/** Another traveller's verification, as a short note after their name. */
export const PERSON_TIER_LABELS: Record<VerificationTier, string> = {
  NONE: 'not verified',
  EMAIL: 'email verified',
  PHONE: 'phone verified',
  SELFIE_MATCH: 'selfie verified',
};

export const TRIP_STATUS_LABELS: Record<TripStatus, string> = {
  DRAFT: 'Draft',
  OPEN: 'Open',
  FULL: 'Full',
  LOCKED: 'Locked in',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

/** As the requester reads their own request. */
export const REQUEST_STATUS_LABELS: Record<MemberStatus, string> = {
  REQUESTED: 'Waiting for the host',
  ACCEPTED: 'Accepted',
  REJECTED: 'Not accepted',
  LOCKED: 'Locked in',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
};

/** As the host reads a request on their trip. */
export const INCOMING_STATUS_LABELS: Record<MemberStatus, string> = {
  REQUESTED: 'Waiting for your answer',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
  LOCKED: 'Locked in',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
};

const DAY = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });

/** A trip's calendar dates as "12 Dec 2026 – 14 Dec 2026"; they carry no time, so no zone shifts them. */
export function formatTripDates(startDate: string, endDate: string): string {
  const start = DAY.format(new Date(`${startDate}T00:00:00Z`));
  if (startDate === endDate) {
    return start;
  }
  return `${start} – ${DAY.format(new Date(`${endDate}T00:00:00Z`))}`;
}

export function placesLeft(totalSlots: number, filledSlots: number): string {
  const left = Math.max(totalSlots - filledSlots, 0);
  if (left === 0) {
    return 'No places left';
  }
  return left === 1 ? '1 place left' : `${String(left)} places left`;
}

/** Short names for the dimensions a match reason is about, shown beside the server's sentence. */
export const MATCH_DIMENSION_LABELS: Record<MatchDimension, string> = {
  BUDGET: 'Budget',
  PACE: 'Pace',
  WAKE_TIME: 'Mornings',
  PLANNING: 'Planning',
  SOCIAL_ENERGY: 'Social energy',
  RISK_APPETITE: 'Risk',
  FOOD: 'Food',
  EXPENSES: 'Shared costs',
};
