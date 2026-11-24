import type { MatchStage, Relationship, VenueCategory } from '../api/meetupApi';

/** Words for meet-up codes; the server only ever sends the codes. */

export const STAGE_HEADINGS: Record<MatchStage, string> = {
  VIDEO_CALL: 'Step 1: a video call',
  ARRANGE_MEETUP: 'Step 2: meet in a public place',
  AWAITING_CONFIRMATION: 'Step 2: meet in a public place',
  CONFIRMED: 'Your meet-up is on',
  FEEDBACK: 'How did it feel?',
  WAITING_FOR_OTHER: 'Thanks for telling us',
  LOCKED: 'Your trip is locked',
  DID_NOT_PROCEED: 'This match didn’t proceed',
  CLOSED: 'This match is closed',
};

export const CATEGORY_LABELS: Record<VenueCategory, string> = {
  CAFE: 'Café',
  MALL: 'Mall',
  RESTAURANT: 'Restaurant',
  PUBLIC_LANDMARK: 'Public landmark',
  TRANSIT_HUB: 'Station or bus stand',
};

export const RELATIONSHIP_LABELS: Record<Relationship, string> = {
  PARENT: 'Parent',
  SIBLING: 'Brother or sister',
  PARTNER: 'Partner',
  RELATIVE: 'Other relative',
  FRIEND: 'Friend',
  OTHER: 'Someone else',
};

export const COMFORT_LABELS: Record<number, string> = {
  1: 'Very uncomfortable',
  2: 'Uncomfortable',
  3: 'Okay',
  4: 'Comfortable',
  5: 'Very comfortable',
};

/** "Sat 12 Dec, 4:15 pm" in the venue's own zone, since that is where the meet-up happens. */
export function formatVenueTime(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-IN', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
    timeZone,
  }).format(new Date(instant));
}

/** "4:15 pm" in the venue's zone. */
export function formatVenueClock(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit', timeZone }).format(new Date(instant));
}

/** Every start time in a window, 15 minutes apart, both ends included. */
export function startTimes(earliest: string, latest: string): string[] {
  const step = 15 * 60 * 1000;
  const times: string[] = [];
  for (let t = Date.parse(earliest); t <= Date.parse(latest); t += step) {
    times.push(new Date(t).toISOString());
  }
  return times;
}

/** "Sat 12 Dec", the venue's local day. */
export function formatVenueDay(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone }).format(
    new Date(instant),
  );
}
