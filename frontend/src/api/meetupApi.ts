import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord, type VerificationTier } from './accessGrant';
import { apiClient } from './client';

export const MATCH_STAGES = [
  'VIDEO_CALL',
  'ARRANGE_MEETUP',
  'AWAITING_CONFIRMATION',
  'CONFIRMED',
  'FEEDBACK',
  'WAITING_FOR_OTHER',
  'LOCKED',
  'DID_NOT_PROCEED',
  'CLOSED',
] as const;
export const MEETUP_STATUSES = ['PROPOSED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'] as const;
export const VENUE_CATEGORIES = ['CAFE', 'MALL', 'RESTAURANT', 'PUBLIC_LANDMARK', 'TRANSIT_HUB'] as const;
export const RELATIONSHIPS = ['PARENT', 'SIBLING', 'PARTNER', 'RELATIVE', 'FRIEND', 'OTHER'] as const;
export const CANCELLATION_TYPES = ['SAFETY', 'CONVENIENCE'] as const;
const ROLES = ['HOST', 'COMPANION'] as const;
const TIERS = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'] as const satisfies readonly VerificationTier[];

export type MatchStage = (typeof MATCH_STAGES)[number];
export type MeetupStatus = (typeof MEETUP_STATUSES)[number];
export type VenueCategory = (typeof VENUE_CATEGORIES)[number];
export type Relationship = (typeof RELATIONSHIPS)[number];
export type CancellationType = (typeof CANCELLATION_TYPES)[number];

export interface Venue {
  id: string;
  name: string;
  address: string;
  city: string;
  category: VenueCategory;
  latitude: number;
  longitude: number;
  /** The zone the venue's times are shown in. */
  timeZone: string;
}

export interface Meetup {
  id: string;
  venue: Venue;
  scheduledAt: string;
  status: MeetupStatus;
  proposedByMe: boolean;
  myPlusOne: boolean;
  theirPlusOne: boolean;
  checkInOpensAt: string;
  checkInClosesAt: string;
  iArrived: boolean;
  /** Whether the other person has checked in; never where they are. */
  theyArrived: boolean;
  iConfirmedSafe: boolean;
  /** Set only for a change-of-plan cancellation. */
  cancellation: 'CONVENIENCE' | null;
}

/** MatchResponse: one match's place on the trust ladder, from the caller's side. */
export interface Match {
  matchId: string;
  myRole: (typeof ROLES)[number];
  trip: { id: string; title: string; destinationName: string; startDate: string };
  otherPerson: { firstName: string; verificationTier: VerificationTier };
  stage: MatchStage;
  /** Null once the match did not proceed. */
  videoCall: { confirmedByMe: boolean; confirmedByThem: boolean; verified: boolean } | null;
  emergencyContactSet: boolean;
  /** The latest meet-up; always null once the match did not proceed. */
  meetup: Meetup | null;
  myFeedbackGiven: boolean;
}

/** Start times allowed on one local date, both ends included, on a 15-minute grid. */
export interface DaylightWindow {
  date: string;
  earliest: string;
  latest: string;
}

export interface VenueOption {
  venue: Venue;
  distanceKm: number;
  timeZone: string;
  windows: DaylightWindow[];
}

export interface EmergencyContact {
  name: string;
  relationship: Relationship;
  /** Null if the stored number can no longer be read. */
  maskedPhone: string | null;
  updatedAt: string;
}

export interface Proposal {
  venueId: string;
  scheduledAt: string;
  bringingPlusOne: boolean;
}

export interface CheckIn {
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
  selfie: Blob;
}

export function createMeetupApi(client: AxiosInstance) {
  const base = (matchId: string) => `/matches/${encodeURIComponent(matchId)}`;
  const match = async (request: Promise<{ data: unknown }>) => readMatch((await request).data);
  return {
    match: (matchId: string) => match(client.get<unknown>(base(matchId))),

    async venues(
      matchId: string,
      near: { latitude: number; longitude: number; radiusKm: number } | null,
    ): Promise<VenueOption[]> {
      const response = await client.get<unknown>(`${base(matchId)}/venues`, { params: near ?? {} });
      const data = response.data;
      if (isRecord(data) && Array.isArray(data['venues'])) {
        return data['venues'].map(readVenueOption);
      }
      throw new Error('Unexpected venues.');
    },

    confirmVideoCall: (matchId: string) => match(client.post<unknown>(`${base(matchId)}/video-call`)),

    propose: (matchId: string, proposal: Proposal) => match(client.post<unknown>(`${base(matchId)}/meetup`, proposal)),

    confirm: (matchId: string, bringingPlusOne: boolean) =>
      match(client.post<unknown>(`${base(matchId)}/meetup/confirm`, { bringingPlusOne })),

    cancel: (matchId: string, type: CancellationType) =>
      match(client.post<unknown>(`${base(matchId)}/meetup/cancel`, { type })),

    markSafe: (matchId: string) => match(client.post<unknown>(`${base(matchId)}/meetup/safe`)),

    rate: (matchId: string, comfortRating: number, notes: string) =>
      match(
        client.post<unknown>(`${base(matchId)}/meetup/feedback`, {
          comfortRating,
          notes: notes.trim() === '' ? null : notes.trim(),
        }),
      ),

    async checkIn(matchId: string, checkIn: CheckIn): Promise<{ arrived: boolean; meetupCompleted: boolean }> {
      const form = new FormData();
      form.append('latitude', String(checkIn.latitude));
      form.append('longitude', String(checkIn.longitude));
      if (checkIn.accuracyMeters !== null) {
        form.append('accuracyMeters', String(Math.round(checkIn.accuracyMeters)));
      }
      form.append('selfie', checkIn.selfie, 'selfie.jpg');
      const response = await client.post<unknown>(`${base(matchId)}/meetup/check-in`, form);
      const data = response.data;
      if (isRecord(data) && typeof data['arrived'] === 'boolean' && typeof data['meetupCompleted'] === 'boolean') {
        return { arrived: data['arrived'], meetupCompleted: data['meetupCompleted'] };
      }
      throw new Error('Unexpected check-in.');
    },

    async emergencyContact(): Promise<EmergencyContact | null> {
      const response = await client.get<unknown>('/users/me/emergency-contact');
      return readContactBody(response.data);
    },

    async setEmergencyContact(body: { name: string; relationship: Relationship; phoneNumber: string }) {
      const response = await client.put<unknown>('/users/me/emergency-contact', body);
      const contact = readContactBody(response.data);
      if (contact === null) {
        throw new Error('Unexpected emergency contact.');
      }
      return contact;
    },

    async removeEmergencyContact(): Promise<void> {
      await client.delete('/users/me/emergency-contact');
    },
  };
}

export type MeetupApi = ReturnType<typeof createMeetupApi>;

export const meetupApi: MeetupApi = createMeetupApi(apiClient);

/**
 * A match is accepted only when it agrees with itself: a stage that needs a
 * meet-up has one in the right status, and a match that did not proceed
 * carries nothing else at all.
 */
function readMatch(raw: unknown): Match {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['matchId']) &&
    isRecord(raw['trip']) &&
    isRecord(raw['otherPerson']) &&
    typeof raw['emergencyContactSet'] === 'boolean' &&
    typeof raw['myFeedbackGiven'] === 'boolean'
  ) {
    const trip = raw['trip'];
    const other = raw['otherPerson'];
    const stage = oneOf(MATCH_STAGES, raw['stage']);
    const myRole = oneOf(ROLES, raw['myRole']);
    const tier = oneOf(TIERS, other['verificationTier']);
    const videoCall = raw['videoCall'] === null ? null : readVideoCall(raw['videoCall']);
    const meetup = raw['meetup'] === null ? null : readMeetup(raw['meetup']);
    if (
      stage !== null &&
      myRole !== null &&
      tier !== null &&
      isNonEmptyString(trip['id']) &&
      isNonEmptyString(trip['title']) &&
      isNonEmptyString(trip['destinationName']) &&
      isDate(trip['startDate']) &&
      isNonEmptyString(other['firstName']) &&
      fitsStage(stage, videoCall, meetup)
    ) {
      return {
        matchId: raw['matchId'],
        myRole,
        trip: { id: trip['id'], title: trip['title'], destinationName: trip['destinationName'], startDate: trip['startDate'] },
        otherPerson: { firstName: other['firstName'], verificationTier: tier },
        stage,
        videoCall,
        emergencyContactSet: raw['emergencyContactSet'],
        meetup,
        myFeedbackGiven: raw['myFeedbackGiven'],
      };
    }
  }
  throw new Error('Unexpected match.');
}

function fitsStage(stage: MatchStage, videoCall: Match['videoCall'], meetup: Meetup | null): boolean {
  switch (stage) {
    case 'DID_NOT_PROCEED':
      return videoCall === null && meetup === null;
    case 'AWAITING_CONFIRMATION':
      return videoCall !== null && meetup?.status === 'PROPOSED';
    case 'CONFIRMED':
      return videoCall !== null && meetup?.status === 'CONFIRMED';
    case 'FEEDBACK':
    case 'WAITING_FOR_OTHER':
      return videoCall !== null && meetup?.status === 'COMPLETED';
    default:
      return videoCall !== null;
  }
}

function readVideoCall(raw: unknown): NonNullable<Match['videoCall']> {
  if (
    isRecord(raw) &&
    typeof raw['confirmedByMe'] === 'boolean' &&
    typeof raw['confirmedByThem'] === 'boolean' &&
    typeof raw['verified'] === 'boolean'
  ) {
    return { confirmedByMe: raw['confirmedByMe'], confirmedByThem: raw['confirmedByThem'], verified: raw['verified'] };
  }
  throw new Error('Unexpected video call.');
}

function readMeetup(raw: unknown): Meetup {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isInstant(raw['scheduledAt']) &&
    isInstant(raw['checkInOpensAt']) &&
    isInstant(raw['checkInClosesAt']) &&
    typeof raw['proposedByMe'] === 'boolean' &&
    typeof raw['myPlusOne'] === 'boolean' &&
    typeof raw['theirPlusOne'] === 'boolean' &&
    typeof raw['iArrived'] === 'boolean' &&
    typeof raw['theyArrived'] === 'boolean' &&
    typeof raw['iConfirmedSafe'] === 'boolean' &&
    (raw['cancellation'] === null || raw['cancellation'] === 'CONVENIENCE')
  ) {
    const status = oneOf(MEETUP_STATUSES, raw['status']);
    if (status !== null) {
      return {
        id: raw['id'],
        venue: readVenue(raw['venue']),
        scheduledAt: raw['scheduledAt'],
        status,
        proposedByMe: raw['proposedByMe'],
        myPlusOne: raw['myPlusOne'],
        theirPlusOne: raw['theirPlusOne'],
        checkInOpensAt: raw['checkInOpensAt'],
        checkInClosesAt: raw['checkInClosesAt'],
        iArrived: raw['iArrived'],
        theyArrived: raw['theyArrived'],
        iConfirmedSafe: raw['iConfirmedSafe'],
        cancellation: raw['cancellation'],
      };
    }
  }
  throw new Error('Unexpected meet-up.');
}

function readVenue(raw: unknown): Venue {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['name']) &&
    isNonEmptyString(raw['address']) &&
    isNonEmptyString(raw['city']) &&
    isCoordinate(raw['latitude'], 90) &&
    isCoordinate(raw['longitude'], 180) &&
    isTimeZone(raw['timeZone'])
  ) {
    const category = oneOf(VENUE_CATEGORIES, raw['category']);
    if (category !== null) {
      return {
        id: raw['id'],
        name: raw['name'],
        address: raw['address'],
        city: raw['city'],
        category,
        latitude: raw['latitude'],
        longitude: raw['longitude'],
        timeZone: raw['timeZone'],
      };
    }
  }
  throw new Error('Unexpected venue.');
}

function readVenueOption(raw: unknown): VenueOption {
  if (
    isRecord(raw) &&
    typeof raw['distanceKm'] === 'number' &&
    Number.isFinite(raw['distanceKm']) &&
    raw['distanceKm'] >= 0 &&
    isTimeZone(raw['timeZone']) &&
    Array.isArray(raw['windows'])
  ) {
    return {
      venue: readVenue(raw['venue']),
      distanceKm: raw['distanceKm'],
      timeZone: raw['timeZone'],
      windows: raw['windows'].map(readWindow),
    };
  }
  throw new Error('Unexpected venue option.');
}

function readWindow(raw: unknown): DaylightWindow {
  if (
    isRecord(raw) &&
    isDate(raw['date']) &&
    isInstant(raw['earliest']) &&
    isInstant(raw['latest']) &&
    Date.parse(raw['earliest']) <= Date.parse(raw['latest'])
  ) {
    return { date: raw['date'], earliest: raw['earliest'], latest: raw['latest'] };
  }
  throw new Error('Unexpected daylight window.');
}

function readContactBody(raw: unknown): EmergencyContact | null {
  if (!isRecord(raw)) {
    throw new Error('Unexpected emergency contact.');
  }
  const contact = raw['contact'];
  if (contact === null) {
    return null;
  }
  if (
    isRecord(contact) &&
    isNonEmptyString(contact['name']) &&
    (contact['maskedPhone'] === null || isNonEmptyString(contact['maskedPhone'])) &&
    isInstant(contact['updatedAt'])
  ) {
    const relationship = oneOf(RELATIONSHIPS, contact['relationship']);
    if (relationship !== null) {
      return { name: contact['name'], relationship, maskedPhone: contact['maskedPhone'], updatedAt: contact['updatedAt'] };
    }
  }
  throw new Error('Unexpected emergency contact.');
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value));
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function isCoordinate(value: unknown, limit: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= limit;
}

/** Only zones the browser itself knows, so formatting can never throw on a bad one. */
function isTimeZone(value: unknown): value is string {
  if (typeof value !== 'string' || value.length > 64) {
    return false;
  }
  try {
    new Intl.DateTimeFormat('en', { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
