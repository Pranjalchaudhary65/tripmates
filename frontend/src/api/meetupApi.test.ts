import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { match, MATCH_ID, meetup, venueOptions } from '../test/meetupFixtures';
import { createApiClient } from './createApiClient';
import { createMeetupApi } from './meetupApi';

const BASE_URL = 'http://api.test/api/v1';

const server = setupServer();
const api = createMeetupApi(createApiClient(BASE_URL));

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function serve(body: Record<string, unknown>) {
  server.use(http.get(`${BASE_URL}/matches/${MATCH_ID}`, () => HttpResponse.json(body)));
}

describe('meet-up API', () => {
  it('reads a match whose stage and meet-up agree', async () => {
    serve(match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED' }) }));

    const result = await api.match(MATCH_ID);

    expect(result.stage).toBe('CONFIRMED');
    expect(result.meetup?.venue.timeZone).toBe('Asia/Kolkata');
  });

  it('reads a match that did not proceed only when it carries nothing else', async () => {
    serve(match('DID_NOT_PROCEED'));
    expect((await api.match(MATCH_ID)).stage).toBe('DID_NOT_PROCEED');
  });

  it.each([
    ['a stopped match that still shows its meet-up', match('DID_NOT_PROCEED', { meetup: meetup() })],
    ['a stopped match that still shows the video call', match('DID_NOT_PROCEED', { videoCall: { confirmedByMe: true, confirmedByThem: true, verified: true } })],
    ['a confirmed stage with a proposed meet-up', match('CONFIRMED', { meetup: meetup({ status: 'PROPOSED' }) })],
    ['a confirmed stage with no meet-up', match('CONFIRMED')],
    ['an unknown stage', match('BOOKED')],
    ['a venue in a time zone the browser does not know', match('AWAITING_CONFIRMATION', { meetup: meetup({ venue: { ...meetup().venue, timeZone: 'Mars/Olympus' } }) })],
    ['an arrival given as a place', match('CONFIRMED', { meetup: meetup({ status: 'CONFIRMED', theyArrived: { latitude: 1 } }) })],
  ])('refuses %s', async (_name, body) => {
    serve(body);
    await expect(api.match(MATCH_ID)).rejects.toThrow();
  });

  it('asks for venues near a point only when one is given', async () => {
    const queries: string[] = [];
    server.use(
      http.get(`${BASE_URL}/matches/${MATCH_ID}/venues`, ({ request }) => {
        queries.push(new URL(request.url).search);
        return HttpResponse.json(venueOptions());
      }),
    );

    const near = await api.venues(MATCH_ID, null);
    await api.venues(MATCH_ID, { latitude: 15.3, longitude: 74.1, radiusKm: 25 });

    expect(near[0]?.windows).toHaveLength(2);
    expect(queries[0]).toBe('');
    expect(queries[1]).toContain('latitude=15.3');
  });

  it('refuses a daylight window that ends before it starts', async () => {
    const options = venueOptions();
    const first = options.venues[0];
    if (first !== undefined) {
      first.windows = [{ date: '2026-12-05', earliest: '2026-12-05T10:00:00Z', latest: '2026-12-05T09:00:00Z' }];
    }
    server.use(http.get(`${BASE_URL}/matches/${MATCH_ID}/venues`, () => HttpResponse.json(options)));

    await expect(api.venues(MATCH_ID, null)).rejects.toThrow();
  });

  it('sends a check-in as a form with the location and the selfie', async () => {
    let form: FormData | null = null;
    server.use(
      http.post(`${BASE_URL}/matches/${MATCH_ID}/meetup/check-in`, async ({ request }) => {
        form = await request.formData();
        return HttpResponse.json({ arrived: true, meetupCompleted: false });
      }),
    );

    await api.checkIn(MATCH_ID, { latitude: 14.54, longitude: 74.31, accuracyMeters: 12.4, selfie: new Blob(['x']) });

    const sent = form as FormData | null;
    expect(sent?.get('latitude')).toBe('14.54');
    expect(sent?.get('accuracyMeters')).toBe('12');
    expect(sent?.get('selfie')).toBeInstanceOf(Blob);
  });

  it('sends blank notes as null', async () => {
    let body: unknown = null;
    server.use(
      http.post(`${BASE_URL}/matches/${MATCH_ID}/meetup/feedback`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(match('WAITING_FOR_OTHER', { meetup: meetup({ status: 'COMPLETED' }), myFeedbackGiven: true }));
      }),
    );

    await api.rate(MATCH_ID, 4, '   ');

    expect(body).toEqual({ comfortRating: 4, notes: null });
  });
});
