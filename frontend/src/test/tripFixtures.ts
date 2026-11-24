export const TRIP_ID = '6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b';
export const OTHER_TRIP_ID = '7a2d3c4b-5e6f-4071-9b8c-0d1e2f3a4b5c';
export const REQUEST_ID = '8b3e4d5c-6f70-4182-ac9d-1e2f3a4b5c6d';
export const OTHER_REQUEST_ID = '9c4f5e6d-7081-4293-bdae-2f3a4b5c6d7e';

/** TripSummaryResponse as the backend writes it: someone else's open trip by default. */
export function tripSummary(overrides: Record<string, unknown> = {}) {
  return {
    id: TRIP_ID,
    title: 'Monsoon weekend in Gokarna',
    destinationName: 'Gokarna, Karnataka',
    latitude: 14.5479,
    longitude: 74.3188,
    startDate: '2026-12-12',
    endDate: '2026-12-14',
    totalSlots: 3,
    filledSlots: 1,
    budgetBand: 'MID',
    status: 'OPEN',
    distanceKm: null,
    host: { firstName: 'Asha', verificationTier: 'PHONE' },
    hostedByMe: false,
    myRequest: null,
    pendingRequests: null,
    ...overrides,
  };
}

/** TripDetailResponse. */
export function tripDetail(summary: Record<string, unknown> = {}, overrides: Record<string, unknown> = {}) {
  return {
    trip: tripSummary(summary),
    description: 'Beach hopping, easy pace.\nHostel stays.',
    radiusKm: 25,
    createdAt: '2026-11-20T10:00:00Z',
    ...overrides,
  };
}

/** PageResponse. */
export function pageOf<T>(items: T[], options: { page?: number; totalPages?: number; totalItems?: number } = {}) {
  return {
    items,
    page: options.page ?? 0,
    size: 20,
    totalItems: options.totalItems ?? items.length,
    totalPages: options.totalPages ?? (items.length === 0 ? 0 : 1),
  };
}

const TRIP_REF = {
  id: TRIP_ID,
  title: 'Monsoon weekend in Gokarna',
  destinationName: 'Gokarna, Karnataka',
  startDate: '2026-12-12',
  endDate: '2026-12-14',
  status: 'OPEN',
  totalSlots: 3,
  filledSlots: 1,
};

/** IncomingRequestResponse: a request on the caller's trip. */
export function incomingRequest(overrides: Record<string, unknown> = {}) {
  return {
    id: REQUEST_ID,
    status: 'REQUESTED',
    requestedAt: '2026-11-22T09:30:00Z',
    decidedAt: null,
    trip: TRIP_REF,
    requester: { firstName: 'Ravi', verificationTier: 'SELFIE_MATCH' },
    ...overrides,
  };
}

/** OutgoingRequestResponse: one of the caller's own requests. */
export function outgoingRequest(overrides: Record<string, unknown> = {}) {
  return {
    id: REQUEST_ID,
    status: 'REQUESTED',
    requestedAt: '2026-11-22T09:30:00Z',
    decidedAt: null,
    trip: TRIP_REF,
    host: { firstName: 'Asha', verificationTier: 'PHONE' },
    ...overrides,
  };
}

/** JoinDecisionResponse. */
export function decision(status: string, trip: { status?: string; filledSlots?: number } = {}) {
  return {
    id: REQUEST_ID,
    status,
    decidedAt: '2026-11-23T10:00:00Z',
    trip: { id: TRIP_ID, status: trip.status ?? 'OPEN', totalSlots: 3, filledSlots: trip.filledSlots ?? 2 },
  };
}
