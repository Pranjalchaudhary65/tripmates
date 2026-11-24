import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord, type VerificationTier } from './accessGrant';
import { apiClient } from './client';
import { BUDGET_BANDS, type BudgetBand } from './vibeApi';
import type { Page } from './verificationApi';

export const TRIP_STATUSES = ['DRAFT', 'OPEN', 'FULL', 'LOCKED', 'COMPLETED', 'CANCELLED'] as const;
export const MEMBER_STATUSES = ['REQUESTED', 'ACCEPTED', 'REJECTED', 'LOCKED', 'CANCELLED', 'COMPLETED'] as const;
const TIERS = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'] as const satisfies readonly VerificationTier[];

export type TripStatus = (typeof TRIP_STATUSES)[number];
export type MemberStatus = (typeof MEMBER_STATUSES)[number];

/** Someone else on a trip: a first name and a tier, never an ID or contact. */
export interface Person {
  firstName: string;
  verificationTier: VerificationTier;
}

/** TripSummaryResponse: one trip in a list, as the caller sees it. */
export interface TripSummary {
  id: string;
  title: string;
  destinationName: string;
  latitude: number;
  longitude: number;
  startDate: string;
  endDate: string;
  totalSlots: number;
  filledSlots: number;
  budgetBand: BudgetBand;
  status: TripStatus;
  distanceKm: number | null;
  host: Person;
  hostedByMe: boolean;
  myRequest: { id: string; status: MemberStatus } | null;
  pendingRequests: number | null;
}

/** TripDetailResponse. */
export interface TripDetail {
  trip: TripSummary;
  description: string;
  radiusKm: number;
  createdAt: string;
}

/** TripRefResponse: the trip a request is about. */
export interface TripRef {
  id: string;
  title: string;
  destinationName: string;
  startDate: string;
  endDate: string;
  status: TripStatus;
  totalSlots: number;
  filledSlots: number;
}

/** IncomingRequestResponse and OutgoingRequestResponse share this shape; only the person differs. */
export interface JoinRequest {
  id: string;
  status: MemberStatus;
  requestedAt: string;
  decidedAt: string | null;
  trip: TripRef;
  person: Person;
}

/** JoinDecisionResponse. */
export interface JoinDecision {
  id: string;
  status: MemberStatus;
  decidedAt: string | null;
  trip: { id: string; status: TripStatus; totalSlots: number; filledSlots: number };
}

/** JoinRequestCreatedResponse. */
export interface CreatedJoinRequest {
  id: string;
  tripId: string;
  status: MemberStatus;
  requestedAt: string;
}

/** CreateTripRequest. */
export interface NewTrip {
  title: string;
  description: string;
  destinationName: string;
  latitude: number;
  longitude: number;
  radiusKm: number;
  startDate: string;
  endDate: string;
  totalSlots: number;
  budgetBand: BudgetBand;
}

/** The browse query; absent parts are left out of the URL. */
export interface TripSearch {
  near: { latitude: number; longitude: number; radiusKm: number } | null;
  from: string | null;
  to: string | null;
  budgets: readonly BudgetBand[];
  page: number;
}

export function createTripsApi(client: AxiosInstance) {
  return {
    async browse(search: TripSearch): Promise<Page<TripSummary>> {
      const params = new URLSearchParams();
      if (search.near !== null) {
        params.set('latitude', String(search.near.latitude));
        params.set('longitude', String(search.near.longitude));
        params.set('radiusKm', String(search.near.radiusKm));
      }
      if (search.from !== null) {
        params.set('from', search.from);
      }
      if (search.to !== null) {
        params.set('to', search.to);
      }
      for (const budget of search.budgets) {
        params.append('budget', budget);
      }
      params.set('page', String(search.page));
      const response = await client.get<unknown>('/trips', { params });
      return readPage(response.data, readSummary);
    },

    async hosted(page: number): Promise<Page<TripSummary>> {
      const response = await client.get<unknown>('/trips/hosted', { params: { page } });
      return readPage(response.data, readSummary);
    },

    async detail(tripId: string): Promise<TripDetail> {
      const response = await client.get<unknown>(`/trips/${encodeURIComponent(tripId)}`);
      return readDetail(response.data);
    },

    async create(trip: NewTrip): Promise<TripDetail> {
      const response = await client.post<unknown>('/trips', trip);
      return readDetail(response.data);
    },

    async requestToJoin(tripId: string): Promise<CreatedJoinRequest> {
      const response = await client.post<unknown>(`/trips/${encodeURIComponent(tripId)}/join-requests`);
      return readCreated(response.data);
    },

    async incoming(options: { status: MemberStatus | null; tripId: string | null; page: number }): Promise<
      Page<JoinRequest>
    > {
      const params = new URLSearchParams({ page: String(options.page) });
      if (options.status !== null) {
        params.set('status', options.status);
      }
      if (options.tripId !== null) {
        params.set('tripId', options.tripId);
      }
      const response = await client.get<unknown>('/join-requests/incoming', { params });
      return readPage(response.data, (raw) => readJoinRequest(raw, 'requester'));
    },

    async outgoing(options: { status: MemberStatus | null; page: number }): Promise<Page<JoinRequest>> {
      const params = new URLSearchParams({ page: String(options.page) });
      if (options.status !== null) {
        params.set('status', options.status);
      }
      const response = await client.get<unknown>('/join-requests/outgoing', { params });
      return readPage(response.data, (raw) => readJoinRequest(raw, 'host'));
    },

    async accept(requestId: string): Promise<JoinDecision> {
      const response = await client.post<unknown>(`/join-requests/${encodeURIComponent(requestId)}/accept`);
      return readDecision(response.data);
    },

    async reject(requestId: string): Promise<JoinDecision> {
      const response = await client.post<unknown>(`/join-requests/${encodeURIComponent(requestId)}/reject`);
      return readDecision(response.data);
    },
  };
}

export type TripsApi = ReturnType<typeof createTripsApi>;

export const tripsApi: TripsApi = createTripsApi(apiClient);

function readSummary(raw: unknown): TripSummary {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['title']) &&
    isNonEmptyString(raw['destinationName']) &&
    isCoordinate(raw['latitude'], 90) &&
    isCoordinate(raw['longitude'], 180) &&
    isDate(raw['startDate']) &&
    isDate(raw['endDate']) &&
    isCount(raw['totalSlots']) &&
    isCount(raw['filledSlots']) &&
    typeof raw['hostedByMe'] === 'boolean' &&
    (raw['distanceKm'] === null || isFiniteNonNegative(raw['distanceKm'])) &&
    (raw['pendingRequests'] === null || isCount(raw['pendingRequests']))
  ) {
    const budgetBand = oneOf(BUDGET_BANDS, raw['budgetBand']);
    const status = oneOf(TRIP_STATUSES, raw['status']);
    const host = readPerson(raw['host']);
    const myRequest = readMyRequest(raw['myRequest']);
    if (budgetBand !== null && status !== null && myRequest !== undefined) {
      return {
        id: raw['id'],
        title: raw['title'],
        destinationName: raw['destinationName'],
        latitude: raw['latitude'],
        longitude: raw['longitude'],
        startDate: raw['startDate'],
        endDate: raw['endDate'],
        totalSlots: raw['totalSlots'],
        filledSlots: raw['filledSlots'],
        budgetBand,
        status,
        distanceKm: raw['distanceKm'],
        host,
        hostedByMe: raw['hostedByMe'],
        myRequest,
        pendingRequests: raw['pendingRequests'],
      };
    }
  }
  throw new Error('Unexpected trip.');
}

function readDetail(raw: unknown): TripDetail {
  if (
    isRecord(raw) &&
    typeof raw['description'] === 'string' &&
    isFiniteNonNegative(raw['radiusKm']) &&
    isNonEmptyString(raw['createdAt'])
  ) {
    return {
      trip: readSummary(raw['trip']),
      description: raw['description'],
      radiusKm: raw['radiusKm'],
      createdAt: raw['createdAt'],
    };
  }
  throw new Error('Unexpected trip detail.');
}

function readPerson(raw: unknown): Person {
  if (isRecord(raw) && isNonEmptyString(raw['firstName'])) {
    const verificationTier = oneOf(TIERS, raw['verificationTier']);
    if (verificationTier !== null) {
      return { firstName: raw['firstName'], verificationTier };
    }
  }
  throw new Error('Unexpected person.');
}

/** undefined for a malformed value, so the caller can tell it from a real null. */
function readMyRequest(raw: unknown): TripSummary['myRequest'] | undefined {
  if (raw === null) {
    return null;
  }
  if (isRecord(raw) && isNonEmptyString(raw['id'])) {
    const status = oneOf(MEMBER_STATUSES, raw['status']);
    if (status !== null) {
      return { id: raw['id'], status };
    }
  }
  return undefined;
}

function readTripRef(raw: unknown): TripRef {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['title']) &&
    isNonEmptyString(raw['destinationName']) &&
    isDate(raw['startDate']) &&
    isDate(raw['endDate']) &&
    isCount(raw['totalSlots']) &&
    isCount(raw['filledSlots'])
  ) {
    const status = oneOf(TRIP_STATUSES, raw['status']);
    if (status !== null) {
      return {
        id: raw['id'],
        title: raw['title'],
        destinationName: raw['destinationName'],
        startDate: raw['startDate'],
        endDate: raw['endDate'],
        status,
        totalSlots: raw['totalSlots'],
        filledSlots: raw['filledSlots'],
      };
    }
  }
  throw new Error('Unexpected trip reference.');
}

function readJoinRequest(raw: unknown, personField: 'requester' | 'host'): JoinRequest {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['requestedAt']) &&
    (raw['decidedAt'] === null || isNonEmptyString(raw['decidedAt']))
  ) {
    const status = oneOf(MEMBER_STATUSES, raw['status']);
    if (status !== null) {
      return {
        id: raw['id'],
        status,
        requestedAt: raw['requestedAt'],
        decidedAt: raw['decidedAt'],
        trip: readTripRef(raw['trip']),
        person: readPerson(raw[personField]),
      };
    }
  }
  throw new Error('Unexpected join request.');
}

function readDecision(raw: unknown): JoinDecision {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    (raw['decidedAt'] === null || isNonEmptyString(raw['decidedAt'])) &&
    isRecord(raw['trip'])
  ) {
    const trip = raw['trip'];
    const status = oneOf(MEMBER_STATUSES, raw['status']);
    const tripStatus = oneOf(TRIP_STATUSES, trip['status']);
    if (
      status !== null &&
      tripStatus !== null &&
      isNonEmptyString(trip['id']) &&
      isCount(trip['totalSlots']) &&
      isCount(trip['filledSlots'])
    ) {
      return {
        id: raw['id'],
        status,
        decidedAt: raw['decidedAt'],
        trip: { id: trip['id'], status: tripStatus, totalSlots: trip['totalSlots'], filledSlots: trip['filledSlots'] },
      };
    }
  }
  throw new Error('Unexpected join decision.');
}

function readCreated(raw: unknown): CreatedJoinRequest {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['tripId']) &&
    isNonEmptyString(raw['requestedAt'])
  ) {
    const status = oneOf(MEMBER_STATUSES, raw['status']);
    if (status !== null) {
      return { id: raw['id'], tripId: raw['tripId'], status, requestedAt: raw['requestedAt'] };
    }
  }
  throw new Error('Unexpected join request.');
}

function readPage<T>(body: unknown, readItem: (raw: unknown) => T): Page<T> {
  if (
    isRecord(body) &&
    Array.isArray(body['items']) &&
    isCount(body['page']) &&
    isCount(body['size']) &&
    isCount(body['totalItems']) &&
    isCount(body['totalPages'])
  ) {
    return {
      items: body['items'].map(readItem),
      page: body['page'],
      size: body['size'],
      totalItems: body['totalItems'],
      totalPages: body['totalPages'],
    };
  }
  throw new Error('Unexpected page.');
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function isFiniteNonNegative(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

function isCoordinate(value: unknown, limit: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= limit;
}

/** ISO calendar date, as Jackson writes a LocalDate. */
function isDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
