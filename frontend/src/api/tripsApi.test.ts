import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import {
  blockedMatch,
  decision,
  incomingRequest,
  outgoingRequest,
  pageOf,
  REQUEST_ID,
  scoredMatch,
  TRIP_ID,
  tripDetail,
  tripSummary,
  unscoredMatch,
} from '../test/tripFixtures';
import { createApiClient } from './createApiClient';
import { createTripsApi } from './tripsApi';

const BASE_URL = 'http://api.test/api/v1';

const server = setupServer();
const api = createTripsApi(createApiClient(BASE_URL));

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

describe('trips API', () => {
  it('sends only the filters that are set, repeating budget for each band', async () => {
    let sent = new URLSearchParams();
    server.use(
      http.get(`${BASE_URL}/trips`, ({ request }) => {
        sent = new URL(request.url).searchParams;
        return HttpResponse.json(pageOf([tripSummary({ distanceKm: 3.2 })]));
      }),
    );

    const page = await api.browse({
      near: { latitude: 15.2993, longitude: 74.124, radiusKm: 100 },
      from: '2026-12-01',
      to: null,
      budgets: ['SHOESTRING', 'MID'],
      page: 2,
    });

    expect(page.items[0]?.distanceKm).toBe(3.2);
    expect(sent.get('latitude')).toBe('15.2993');
    expect(sent.get('longitude')).toBe('74.124');
    expect(sent.get('radiusKm')).toBe('100');
    expect(sent.get('from')).toBe('2026-12-01');
    expect(sent.has('to')).toBe(false);
    expect(sent.getAll('budget')).toEqual(['SHOESTRING', 'MID']);
    expect(sent.get('page')).toBe('2');
  });

  it('reads a trip detail with the caller-specific parts', async () => {
    server.use(
      http.get(`${BASE_URL}/trips/${TRIP_ID}`, () =>
        HttpResponse.json(tripDetail({ myRequest: { id: REQUEST_ID, status: 'REQUESTED' } })),
      ),
    );

    const detail = await api.detail(TRIP_ID);

    expect(detail.trip.myRequest).toEqual({ id: REQUEST_ID, status: 'REQUESTED' });
    expect(detail.trip.host).toEqual({ firstName: 'Asha', verificationTier: 'PHONE' });
    expect(detail.radiusKm).toBe(25);
  });

  it.each([
    ['status', 'GONE'],
    ['budgetBand', 'FREE'],
    ['totalSlots', -1],
    ['filledSlots', 1.5],
    ['latitude', 91],
    ['startDate', '12/12/2026'],
    ['host', { firstName: 'Asha', verificationTier: 'ROYAL' }],
    ['host', { firstName: '', verificationTier: 'EMAIL' }],
    ['myRequest', { id: REQUEST_ID, status: 'MAYBE' }],
    ['distanceKm', -2],
  ])('refuses a trip whose %s is %j', async (field, value) => {
    server.use(http.get(`${BASE_URL}/trips/${TRIP_ID}`, () => HttpResponse.json(tripDetail({ [field]: value }))));

    await expect(api.detail(TRIP_ID)).rejects.toThrow();
  });

  it('puts the person under the right name for each side of a request', async () => {
    server.use(
      http.get(`${BASE_URL}/join-requests/incoming`, () => HttpResponse.json(pageOf([incomingRequest()]))),
      http.get(`${BASE_URL}/join-requests/outgoing`, () => HttpResponse.json(pageOf([outgoingRequest()]))),
    );

    const incoming = await api.incoming({ status: null, tripId: null, page: 0 });
    const outgoing = await api.outgoing({ status: null, page: 0 });

    expect(incoming.items[0]?.person.firstName).toBe('Ravi');
    expect(outgoing.items[0]?.person.firstName).toBe('Asha');
  });

  it('narrows the inbox by status and trip only when asked', async () => {
    const queries: string[] = [];
    server.use(
      http.get(`${BASE_URL}/join-requests/incoming`, ({ request }) => {
        queries.push(new URL(request.url).search);
        return HttpResponse.json(pageOf([]));
      }),
    );

    await api.incoming({ status: 'REQUESTED', tripId: TRIP_ID, page: 0 });
    await api.incoming({ status: null, tripId: null, page: 1 });

    expect(queries).toEqual([`?page=0&status=REQUESTED&tripId=${TRIP_ID}`, '?page=1']);
  });

  it('reads a decision with the trip it left behind', async () => {
    server.use(
      http.post(`${BASE_URL}/join-requests/${REQUEST_ID}/accept`, () =>
        HttpResponse.json(decision('ACCEPTED', { status: 'FULL', filledSlots: 3 })),
      ),
    );

    const result = await api.accept(REQUEST_ID);

    expect(result.status).toBe('ACCEPTED');
    expect(result.trip).toEqual({ id: TRIP_ID, status: 'FULL', totalSlots: 3, filledSlots: 3 });
  });

  it('escapes an ID before it goes into a path', async () => {
    let path = '';
    server.use(
      http.post(`${BASE_URL}/trips/*`, ({ request }) => {
        path = new URL(request.url).pathname;
        return HttpResponse.json({ id: REQUEST_ID, tripId: TRIP_ID, status: 'REQUESTED', requestedAt: '2026-11-23T10:00:00Z' });
      }),
    );

    await api.requestToJoin('../admin');

    expect(path).toBe('/api/v1/trips/..%2Fadmin/join-requests');
  });

  it('reads a scored match and a blocked one', async () => {
    server.use(
      http.get(`${BASE_URL}/trips/${TRIP_ID}`, () =>
        HttpResponse.json(tripDetail({ matchScore: 82 }, { match: scoredMatch(82) })),
      ),
    );
    const scored = await api.detail(TRIP_ID);
    expect(scored.trip.matchScore).toBe(82);
    expect(scored.match?.score).toBe(82);
    expect(scored.match?.matches.map((reason) => reason.dimension)).toEqual(['BUDGET', 'WAKE_TIME']);

    server.use(http.get(`${BASE_URL}/trips/${TRIP_ID}`, () => HttpResponse.json(tripDetail({}, { match: blockedMatch() }))));
    const blocked = await api.detail(TRIP_ID);
    expect(blocked.match?.status).toBe('BLOCKED');
    expect(blocked.match?.score).toBeNull();
    expect(blocked.match?.blockers[0]?.rule).toBe('HOST_GENDER_PREFERENCE');
  });

  it.each([
    ['a score on a match that is not SCORED', { ...unscoredMatch('NEEDS_YOUR_PROFILE'), score: 40 }],
    ['no score on a SCORED match', { ...scoredMatch(), score: null }],
    ['a score above 100', scoredMatch(101)],
    ['a fractional score', scoredMatch(50.5)],
    ['blockers on a SCORED match', { ...scoredMatch(), blockers: blockedMatch().blockers }],
    ['BLOCKED without a blocker', { ...blockedMatch(), blockers: [] }],
    ['an unknown status', { ...scoredMatch(), status: 'GREAT' }],
    ['an unknown dimension', { ...scoredMatch(), matches: [{ dimension: 'ASTROLOGY', label: 'Both Leos.' }] }],
    ['an empty label', { ...scoredMatch(), mismatches: [{ dimension: 'PACE', label: '' }] }],
    ['an unknown deal-breaker', { ...blockedMatch(), blockers: [{ rule: 'HEIGHT', label: 'Too tall.' }] }],
  ])('refuses a match with %s', async (_case, match) => {
    server.use(http.get(`${BASE_URL}/trips/${TRIP_ID}`, () => HttpResponse.json(tripDetail({}, { match }))));

    await expect(api.detail(TRIP_ID)).rejects.toThrow();
  });

  it('refuses a match score outside 0-100 on a card', async () => {
    server.use(http.get(`${BASE_URL}/trips/hosted`, () => HttpResponse.json(pageOf([tripSummary({ matchScore: -1 })]))));

    await expect(api.hosted(0)).rejects.toThrow('Unexpected trip.');
  });

  it('refuses a page without its counts', async () => {
    server.use(http.get(`${BASE_URL}/trips/hosted`, () => HttpResponse.json({ items: [] })));

    await expect(api.hosted(0)).rejects.toThrow('Unexpected page.');
  });
});
