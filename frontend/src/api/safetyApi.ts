import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord } from './accessGrant';
import { apiClient } from './client';

export const PHASES = ['BEFORE', 'ACTIVE', 'ENDED'] as const;
export const CHECK_IN_INTERVALS = [3, 6, 12, 24] as const;
export const PREMORTEM_DIMENSIONS = [
  'BUDGET',
  'PACE',
  'WAKE_TIME',
  'PLANNING',
  'SOCIAL_ENERGY',
  'RISK_APPETITE',
  'FOOD',
  'EXPENSES',
] as const;
const ROLES = ['HOST', 'COMPANION'] as const;

export type Phase = (typeof PHASES)[number];
export type CheckInInterval = (typeof CHECK_IN_INTERVALS)[number];

export interface Buddy {
  firstName: string;
  role: (typeof ROLES)[number];
  sharing: boolean;
  /** When they last said they were safe; never where. */
  lastCheckInAt: string | null;
}

/** LiveTripResponse: the live trip screen. Nobody's location is in it. */
export interface LiveTrip {
  trip: {
    id: string;
    title: string;
    destinationName: string;
    destinationLatitude: number;
    destinationLongitude: number;
    startDate: string;
    endDate: string;
  };
  phase: Phase;
  locked: boolean;
  emergencyContactSet: boolean;
  sharing: { checkInEveryHours: CheckInInterval; noteSet: boolean; startedAt: string } | null;
  nextCheckIn: { dueAt: string; overdue: boolean } | null;
  lastCheckIn: { at: string; kmFromDestination: number | null } | null;
  buddies: Buddy[];
  openSos: { id: string; triggeredAt: string; contactAlerted: boolean } | null;
}

export interface PremortemTopic {
  dimension: (typeof PREMORTEM_DIMENSIONS)[number];
  gap: string;
  question: string;
}

export interface Premortem {
  tripId: string;
  startDate: string;
  locked: boolean;
  myVibeSet: boolean;
  pairs: { firstName: string; vibeSet: boolean; topics: PremortemTopic[] }[];
}

export interface ReportedLocation {
  latitude: number;
  longitude: number;
  accuracyMeters: number | null;
}

export function createSafetyApi(client: AxiosInstance) {
  const trip = (tripId: string) => `/trips/${encodeURIComponent(tripId)}`;
  const live = async (request: Promise<{ data: unknown }>) => readLiveTrip((await request).data);
  return {
    live: (tripId: string) => live(client.get<unknown>(`${trip(tripId)}/live`)),

    share: (tripId: string, checkInEveryHours: CheckInInterval, note: string) =>
      live(
        client.put<unknown>(`${trip(tripId)}/live/sharing`, {
          checkInEveryHours,
          note: note.trim() === '' ? null : note.trim(),
        }),
      ),

    stopSharing: (tripId: string) => live(client.delete<unknown>(`${trip(tripId)}/live/sharing`)),

    checkIn: (tripId: string, location: ReportedLocation | null) =>
      live(client.post<unknown>(`${trip(tripId)}/live/check-in`, location ?? {})),

    sos: (tripId: string, clientSosId: string, location: ReportedLocation | null) =>
      live(client.post<unknown>(`${trip(tripId)}/sos`, { clientSosId, ...(location ?? {}) })),

    resolveSos: (sosId: string) => live(client.post<unknown>(`/sos/${encodeURIComponent(sosId)}/resolve`)),

    async premortem(tripId: string): Promise<Premortem> {
      return readPremortem((await client.get<unknown>(`${trip(tripId)}/premortem`)).data);
    },
  };
}

export type SafetyApi = ReturnType<typeof createSafetyApi>;

export const safetyApi: SafetyApi = createSafetyApi(apiClient);

/** Accepted only when it agrees with itself: no sharing details on a trip that isn't locked, nothing due once it ended. */
export function readLiveTrip(raw: unknown): LiveTrip {
  if (
    isRecord(raw) &&
    isRecord(raw['trip']) &&
    typeof raw['locked'] === 'boolean' &&
    typeof raw['emergencyContactSet'] === 'boolean' &&
    Array.isArray(raw['buddies'])
  ) {
    const trip = raw['trip'];
    const phase = oneOf(PHASES, raw['phase']);
    const sharing = raw['sharing'] === null ? null : readSharing(raw['sharing']);
    const next = raw['nextCheckIn'] === null ? null : readNext(raw['nextCheckIn']);
    const last = raw['lastCheckIn'] === null ? null : readLast(raw['lastCheckIn']);
    const openSos = raw['openSos'] === null ? null : readSos(raw['openSos']);
    if (
      phase !== null &&
      isNonEmptyString(trip['id']) &&
      isNonEmptyString(trip['title']) &&
      isNonEmptyString(trip['destinationName']) &&
      isCoordinate(trip['destinationLatitude'], 90) &&
      isCoordinate(trip['destinationLongitude'], 180) &&
      isDate(trip['startDate']) &&
      isDate(trip['endDate']) &&
      (next === null || sharing !== null) &&
      (sharing === null || raw['locked'])
    ) {
      return {
        trip: {
          id: trip['id'],
          title: trip['title'],
          destinationName: trip['destinationName'],
          destinationLatitude: trip['destinationLatitude'],
          destinationLongitude: trip['destinationLongitude'],
          startDate: trip['startDate'],
          endDate: trip['endDate'],
        },
        phase,
        locked: raw['locked'],
        emergencyContactSet: raw['emergencyContactSet'],
        sharing,
        nextCheckIn: next,
        lastCheckIn: last,
        buddies: raw['buddies'].map(readBuddy),
        openSos,
      };
    }
  }
  throw new Error('Unexpected live trip.');
}

function readSharing(raw: unknown): NonNullable<LiveTrip['sharing']> {
  if (isRecord(raw) && typeof raw['noteSet'] === 'boolean' && isInstant(raw['startedAt'])) {
    const every = CHECK_IN_INTERVALS.find((value) => value === raw['checkInEveryHours']);
    if (every !== undefined) {
      return { checkInEveryHours: every, noteSet: raw['noteSet'], startedAt: raw['startedAt'] };
    }
  }
  throw new Error('Unexpected sharing.');
}

function readNext(raw: unknown): NonNullable<LiveTrip['nextCheckIn']> {
  if (isRecord(raw) && isInstant(raw['dueAt']) && typeof raw['overdue'] === 'boolean') {
    return { dueAt: raw['dueAt'], overdue: raw['overdue'] };
  }
  throw new Error('Unexpected check-in.');
}

function readLast(raw: unknown): NonNullable<LiveTrip['lastCheckIn']> {
  if (
    isRecord(raw) &&
    isInstant(raw['at']) &&
    (raw['kmFromDestination'] === null ||
      (typeof raw['kmFromDestination'] === 'number' &&
        Number.isFinite(raw['kmFromDestination']) &&
        raw['kmFromDestination'] >= 0))
  ) {
    return { at: raw['at'], kmFromDestination: raw['kmFromDestination'] };
  }
  throw new Error('Unexpected check-in.');
}

function readSos(raw: unknown): NonNullable<LiveTrip['openSos']> {
  if (isRecord(raw) && isNonEmptyString(raw['id']) && isInstant(raw['triggeredAt']) && typeof raw['contactAlerted'] === 'boolean') {
    return { id: raw['id'], triggeredAt: raw['triggeredAt'], contactAlerted: raw['contactAlerted'] };
  }
  throw new Error('Unexpected SOS.');
}

function readBuddy(raw: unknown): Buddy {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['firstName']) &&
    typeof raw['sharing'] === 'boolean' &&
    (raw['lastCheckInAt'] === null || isInstant(raw['lastCheckInAt'])) &&
    !('latitude' in raw) &&
    !('longitude' in raw)
  ) {
    const role = oneOf(ROLES, raw['role']);
    if (role !== null) {
      return { firstName: raw['firstName'], role, sharing: raw['sharing'], lastCheckInAt: raw['lastCheckInAt'] };
    }
  }
  throw new Error('Unexpected traveller.');
}

export function readPremortem(raw: unknown): Premortem {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['tripId']) &&
    isDate(raw['startDate']) &&
    typeof raw['locked'] === 'boolean' &&
    typeof raw['myVibeSet'] === 'boolean' &&
    Array.isArray(raw['pairs'])
  ) {
    const myVibeSet = raw['myVibeSet'];
    const pairs = raw['pairs'].map((pair: unknown) => {
      if (isRecord(pair) && isNonEmptyString(pair['firstName']) && typeof pair['vibeSet'] === 'boolean' && Array.isArray(pair['topics'])) {
        const topics = pair['topics'].map(readTopic);
        if (topics.length === 0 || (myVibeSet && pair['vibeSet'])) {
          return { firstName: pair['firstName'], vibeSet: pair['vibeSet'], topics };
        }
      }
      throw new Error('Unexpected pre-mortem pair.');
    });
    return { tripId: raw['tripId'], startDate: raw['startDate'], locked: raw['locked'], myVibeSet, pairs };
  }
  throw new Error('Unexpected pre-mortem.');
}

function readTopic(raw: unknown): PremortemTopic {
  if (isRecord(raw) && isNonEmptyString(raw['gap']) && isNonEmptyString(raw['question'])) {
    const dimension = oneOf(PREMORTEM_DIMENSIONS, raw['dimension']);
    if (dimension !== null) {
      return { dimension, gap: raw['gap'], question: raw['question'] };
    }
  }
  throw new Error('Unexpected pre-mortem topic.');
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

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
