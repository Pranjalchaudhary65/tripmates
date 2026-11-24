// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { pageOf, REQUEST_ID, TRIP_ID, tripDetail, tripSummary } from '../test/tripFixtures';
import { addDays, tripToday } from '../validation/tripRules';

const server = createApiServer();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')));
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function browseReturning(body: ReturnType<typeof pageOf>, queries: URLSearchParams[] = []) {
  server.use(
    apiGet('/trips', ({ request }) => {
      queries.push(new URL(request.url).searchParams);
      return HttpResponse.json(body);
    }),
  );
  return queries;
}

describe('trip browse', () => {
  it('needs a session', async () => {
    renderAt('/trips');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeDefined();
  });

  it('lists trips with their host as a first name and tier only', async () => {
    browseReturning(pageOf([tripSummary()]));
    signIn();
    renderAt('/trips');

    expect(await screen.findByRole('link', { name: 'Monsoon weekend in Gokarna' })).toBeDefined();
    expect(screen.getByText(/Hosted by Asha \(phone verified\)/)).toBeDefined();
    expect(screen.getByText(/2 places left/)).toBeDefined();
    expect(screen.getByText('1 trip')).toBeDefined();
    expect(screen.getByRole('link', { name: 'Monsoon weekend in Gokarna' }).getAttribute('href')).toBe(
      `/trips/${TRIP_ID}`,
    );
  });

  it('shows an empty result plainly, with a way to host one', async () => {
    browseReturning(pageOf([]));
    signIn();
    renderAt('/trips');

    expect(await screen.findByText(/No open trips match these filters/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'host one yourself' }).getAttribute('href')).toBe('/trips/new');
  });

  it('searches around a picked place with dates and budgets, and keeps the search in the URL', async () => {
    const queries = browseReturning(pageOf([]));
    signIn();
    const router = renderAt('/trips');
    await screen.findByText(/No open trips match/);

    fireEvent.change(screen.getByLabelText('Near'), { target: { value: 'goa' } });
    fireEvent.change(screen.getByLabelText('Within'), { target: { value: '250' } });
    fireEvent.change(screen.getByLabelText('Starting on or after'), { target: { value: '2026-12-01' } });
    fireEvent.click(screen.getByLabelText('Shoestring'));
    fireEvent.click(screen.getByLabelText('Luxury'));
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => {
      expect(queries).toHaveLength(2);
    });
    const sent = queries[1];
    expect(sent?.get('latitude')).toBe('15.2993');
    expect(sent?.get('longitude')).toBe('74.124');
    expect(sent?.get('radiusKm')).toBe('250');
    expect(sent?.get('from')).toBe('2026-12-01');
    expect(sent?.getAll('budget')).toEqual(['SHOESTRING', 'LUXURY']);
    expect(router.state.location.search).toBe('?near=goa&radius=250&from=2026-12-01&budget=SHOESTRING&budget=LUXURY');
  });

  it('ignores filters in the URL that it does not know instead of sending them', async () => {
    const queries = browseReturning(pageOf([]));
    signIn();
    renderAt('/trips?near=atlantis&radius=99999&from=soon&budget=FREE&budget=MID&page=-3');

    await screen.findByText(/No open trips match/);
    const sent = queries[0];
    expect(sent?.has('latitude')).toBe(false);
    expect(sent?.has('from')).toBe(false);
    expect(sent?.getAll('budget')).toEqual(['MID']);
    expect(sent?.get('page')).toBe('0');
  });

  it('refuses a backwards date window before asking the server', async () => {
    const queries = browseReturning(pageOf([]));
    signIn();
    renderAt('/trips');
    await screen.findByText(/No open trips match/);

    fireEvent.change(screen.getByLabelText('Starting on or after'), { target: { value: '2026-12-10' } });
    fireEvent.change(screen.getByLabelText('Ending on or before'), { target: { value: '2026-12-01' } });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('The end of the date range cannot be before its start.')).toBeDefined();
    expect(queries).toHaveLength(1);
  });

  it.each([
    [400, 'invalid_location_filter', 'To search near a place, give its latitude, longitude and a radius together.', 'give its latitude'],
    [429, 'rate_limited', 'Too many requests.', 'Too many attempts.'],
    [503, 'temporarily_unavailable', 'This action is temporarily unavailable. Try again shortly.', 'temporarily unavailable'],
  ])('shows a %s from browse in words fit for a person', async (status, code, detail, shown) => {
    server.use(apiGet('/trips', () => problem(status, code, detail, { headers: { 'Retry-After': '30' } })));
    signIn();
    renderAt('/trips');

    expect((await screen.findByRole('alert')).textContent).toContain(shown);
  });

  it('shows a load failure and retries on request', async () => {
    let attempts = 0;
    server.use(
      apiGet('/trips', () => {
        attempts++;
        return attempts === 1 ? HttpResponse.error() : HttpResponse.json(pageOf([tripSummary()]));
      }),
    );
    signIn();
    renderAt('/trips');

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('link', { name: 'Monsoon weekend in Gokarna' })).toBeDefined();
  });

  it('renders a title as text, never as markup', async () => {
    browseReturning(pageOf([tripSummary({ title: '<img src=x onerror=alert(1)>' })]));
    signIn();
    renderAt('/trips');

    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeDefined();
    expect(document.querySelector('img')).toBeNull();
  });

  it('pages through results', async () => {
    const queries = browseReturning(pageOf([tripSummary()], { totalPages: 3, totalItems: 41 }));
    signIn();
    const router = renderAt('/trips');
    await screen.findByText('41 trips');

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await waitFor(() => {
      expect(queries.at(-1)?.get('page')).toBe('1');
    });
    expect(router.state.location.search).toBe('?page=1');
  });
});

describe('trip create', () => {
  async function openForm() {
    signIn();
    renderAt('/trips/new');
    await screen.findByRole('heading', { level: 1, name: 'Host a trip' });
  }

  function fillValidTrip() {
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Monsoon weekend in Gokarna' } });
    fireEvent.change(screen.getByLabelText('What the trip is like'), { target: { value: 'Beach hopping.' } });
    fireEvent.change(screen.getByLabelText('Where on the map'), { target: { value: 'gokarna' } });
  }

  it('checks every field before sending anything', async () => {
    let sent = false;
    server.use(
      apiPost('/trips', () => {
        sent = true;
        return HttpResponse.json(tripDetail(), { status: 201 });
      }),
    );
    await openForm();

    fireEvent.change(screen.getByLabelText('Companions wanted'), { target: { value: '30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open the trip' }));

    expect(await screen.findByText(/Give the trip a title/)).toBeDefined();
    expect(screen.getByText('Say what the trip is about.')).toBeDefined();
    expect(screen.getByText(/Ask for 1 to 20 companions/)).toBeDefined();
    expect(screen.getByLabelText('Latitude').getAttribute('aria-invalid')).toBe('true');
    expect(sent).toBe(false);
  });

  it('fills the coordinates and name from a picked place and opens the trip', async () => {
    let body: unknown = null;
    server.use(
      apiPost('/trips', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(tripDetail({ hostedByMe: true, pendingRequests: 0 }), { status: 201 });
      }),
      apiGet(`/trips/${TRIP_ID}`, () => HttpResponse.json(tripDetail({ hostedByMe: true, pendingRequests: 0 }))),
    );
    await openForm();

    fillValidTrip();
    fireEvent.click(screen.getByRole('button', { name: 'Open the trip' }));

    expect(await screen.findByText('Your trip is open. Travellers can find it and ask to join.')).toBeDefined();
    expect(body).toEqual({
      title: 'Monsoon weekend in Gokarna',
      description: 'Beach hopping.',
      destinationName: 'Gokarna, Karnataka',
      latitude: 14.5479,
      longitude: 74.3188,
      radiusKm: 0,
      startDate: addDays(tripToday(), 7),
      endDate: addDays(tripToday(), 9),
      totalSlots: 2,
      budgetBand: 'MID',
    });
  });

  it('places the server date refusal beside the start date', async () => {
    const detail = 'Pick a start date from today up to a year ahead, and an end date no more than 60 days after it.';
    server.use(
      apiPost('/trips', () =>
        HttpResponse.json(
          { status: 400, code: 'invalid_trip_dates', detail, errors: [{ field: 'startDate', message: detail }] },
          { status: 400 },
        ),
      ),
    );
    await openForm();

    fillValidTrip();
    fireEvent.click(screen.getByRole('button', { name: 'Open the trip' }));

    expect(await screen.findByText(detail)).toBeDefined();
    expect(screen.getByLabelText('Start date').getAttribute('aria-invalid')).toBe('true');
    expect((await screen.findByRole('alert')).textContent).toContain('Check the highlighted fields');
  });

  it.each([
    [409, 'too_many_trips', 'You already host as many upcoming trips as you can. Finish or cancel one first.', 'Finish or cancel one first'],
    [429, 'rate_limited', 'Too many requests.', 'Too many attempts.'],
    [500, 'internal_error', 'java.lang.IllegalStateException at TripService', 'Something went wrong on our side'],
  ])('shows a %s from opening a trip safely', async (status, code, detail, shown) => {
    server.use(apiPost('/trips', () => problem(status, code, detail, { headers: { 'Retry-After': '30' } })));
    await openForm();

    fillValidTrip();
    fireEvent.click(screen.getByRole('button', { name: 'Open the trip' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(shown);
    expect(alert.textContent).not.toContain('java.lang');
  });
});

describe('trip detail', () => {
  function openTrip(summary: Record<string, unknown> = {}) {
    server.use(apiGet(`/trips/${TRIP_ID}`, () => HttpResponse.json(tripDetail(summary))));
    signIn();
    renderAt(`/trips/${TRIP_ID}`);
  }

  it('shows the trip and lets someone else ask to join', async () => {
    server.use(
      apiPost(`/trips/${TRIP_ID}/join-requests`, () =>
        HttpResponse.json(
          { id: REQUEST_ID, tripId: TRIP_ID, status: 'REQUESTED', requestedAt: '2026-11-23T10:00:00Z' },
          { status: 201 },
        ),
      ),
    );
    openTrip();

    expect(await screen.findByRole('heading', { level: 1, name: 'Monsoon weekend in Gokarna' })).toBeDefined();
    expect(screen.getByText('Up to 25 km around it')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Ask to join' }));

    expect((await screen.findByRole('status')).textContent).toContain('Waiting for the host');
    expect(screen.queryByRole('button', { name: 'Ask to join' })).toBeNull();
  });

  it.each([
    [409, 'trip_full', 'This trip has no free places left.'],
    [409, 'trip_closed', 'This trip is no longer taking companions.'],
    [409, 'join_request_exists', 'You have already asked to join this trip.'],
    [409, 'too_many_pending_requests', 'You have too many requests waiting for an answer. Wait for some of them first.'],
  ])('explains a %s %s when asking to join', async (status, code, detail) => {
    server.use(apiPost(`/trips/${TRIP_ID}/join-requests`, () => problem(status, code, detail)));
    openTrip();

    fireEvent.click(await screen.findByRole('button', { name: 'Ask to join' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
    expect(screen.getByRole('button', { name: 'Ask to join' })).toBeDefined();
  });

  it('shows the host the waiting requests instead of a join button', async () => {
    openTrip({ hostedByMe: true, pendingRequests: 2 });

    expect(await screen.findByText(/2 waiting for your answer/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Open the requests' }).getAttribute('href')).toBe(
      `/trips/requests?trip=${TRIP_ID}`,
    );
    expect(screen.queryByRole('button', { name: 'Ask to join' })).toBeNull();
  });

  it('offers no join button on a full trip', async () => {
    openTrip({ status: 'FULL', filledSlots: 3 });

    expect(await screen.findByText('This trip is not taking requests.')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Ask to join' })).toBeNull();
  });

  it('says a trip is not available for a 404, the same as for an unknown trip', async () => {
    server.use(apiGet(`/trips/${TRIP_ID}`, () => problem(404, 'not_found', 'The requested resource does not exist.')));
    signIn();
    renderAt(`/trips/${TRIP_ID}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Trip not available' })).toBeDefined();
  });

  it('never sends an ID that is not a UUID', async () => {
    let calls = 0;
    server.use(
      apiGet('/trips/*', () => {
        calls++;
        return HttpResponse.json(tripDetail());
      }),
    );
    signIn();
    renderAt('/trips/..%2Fadmin');

    expect(await screen.findByRole('heading', { level: 1, name: 'Trip not available' })).toBeDefined();
    expect(calls).toBe(0);
  });
});

describe('my trips', () => {
  it('lists hosted trips with their waiting requests', async () => {
    server.use(
      apiGet('/trips/hosted', () =>
        HttpResponse.json(pageOf([tripSummary({ hostedByMe: true, pendingRequests: 3 })])),
      ),
    );
    signIn();
    renderAt('/trips/mine');

    expect(await screen.findByText(/3 waiting for your answer/)).toBeDefined();
    expect(screen.getByText(/You are hosting/)).toBeDefined();
  });

  it('invites a first trip when there are none', async () => {
    server.use(apiGet('/trips/hosted', () => HttpResponse.json(pageOf([]))));
    signIn();
    renderAt('/trips/mine');

    expect(await screen.findByText(/You are not hosting any trips yet/)).toBeDefined();
  });
});
