// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import {
  decision,
  incomingRequest,
  OTHER_REQUEST_ID,
  outgoingRequest,
  pageOf,
  REQUEST_ID,
  TRIP_ID,
} from '../test/tripFixtures';

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

function inboxReturning(items: unknown[], queries: URLSearchParams[] = []) {
  server.use(
    apiGet('/join-requests/incoming', ({ request }) => {
      queries.push(new URL(request.url).searchParams);
      return HttpResponse.json(pageOf(items));
    }),
  );
  return queries;
}

function row(name: string): HTMLElement {
  const element = screen.getByText(new RegExp(`^${name} \\(`)).closest('li');
  if (element === null) {
    throw new Error(`No request from ${name}`);
  }
  return element;
}

describe('join requests', () => {
  it('needs a session', async () => {
    renderAt('/trips/requests');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeDefined();
  });

  it('shows the requests waiting on the host, by first name and tier', async () => {
    const queries = inboxReturning([incomingRequest()]);
    signIn();
    renderAt('/trips/requests');

    expect(await screen.findByText('Ravi (selfie verified)')).toBeDefined();
    expect(within(row('Ravi')).getByText('Waiting for your answer')).toBeDefined();
    expect(queries[0]?.get('status')).toBe('REQUESTED');
    expect(queries[0]?.has('tripId')).toBe(false);
  });

  it('accepts a request and shows the places it left', async () => {
    inboxReturning([incomingRequest()]);
    server.use(apiPost(`/join-requests/${REQUEST_ID}/accept`, () => HttpResponse.json(decision('ACCEPTED'))));
    signIn();
    renderAt('/trips/requests');

    fireEvent.click(await screen.findByRole('button', { name: 'Accept Ravi' }));

    expect(await within(row('Ravi')).findByText('Accepted')).toBeDefined();
    expect(within(row('Ravi')).getByText(/1 place left/)).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Accept Ravi' })).toBeNull();
  });

  it('rejects a request', async () => {
    inboxReturning([incomingRequest()]);
    server.use(
      apiPost(`/join-requests/${REQUEST_ID}/reject`, () => HttpResponse.json(decision('REJECTED', { filledSlots: 1 }))),
    );
    signIn();
    renderAt('/trips/requests');

    fireEvent.click(await screen.findByRole('button', { name: 'Reject Ravi' }));

    expect(await within(row('Ravi')).findByText('Rejected')).toBeDefined();
  });

  it.each([
    ['trip_full', 'This trip has no free places left.'],
    ['trip_closed', 'This trip is no longer taking companions.'],
    ['join_request_decided', 'This request has already been answered.'],
    ['trip_busy', 'This trip changed while your answer was being saved. Try again.'],
  ])('explains %s on the request it belongs to and keeps the buttons', async (code, detail) => {
    inboxReturning([
      incomingRequest(),
      incomingRequest({ id: OTHER_REQUEST_ID, requester: { firstName: 'Meera', verificationTier: 'EMAIL' } }),
    ]);
    server.use(apiPost(`/join-requests/${REQUEST_ID}/accept`, () => problem(409, code, detail)));
    signIn();
    renderAt('/trips/requests');

    fireEvent.click(await screen.findByRole('button', { name: 'Accept Ravi' }));

    expect((await within(row('Ravi')).findByRole('alert')).textContent).toContain(detail);
    expect(within(row('Meera')).queryByRole('alert')).toBeNull();
    expect(screen.getByRole('button', { name: 'Accept Ravi' })).toBeDefined();
  });

  it('says not found for a request that is no longer the caller to answer, without detail', async () => {
    inboxReturning([incomingRequest()]);
    server.use(
      apiPost(`/join-requests/${REQUEST_ID}/accept`, () =>
        problem(404, 'not_found', 'The requested resource does not exist.'),
      ),
    );
    signIn();
    renderAt('/trips/requests');

    fireEvent.click(await screen.findByRole('button', { name: 'Accept Ravi' }));

    expect((await screen.findByRole('alert')).textContent).toContain('That could not be found.');
  });

  it('narrows to one trip from the link on the trip page, and drops a trip filter that is not a UUID', async () => {
    const queries = inboxReturning([]);
    signIn();
    renderAt(`/trips/requests?trip=${TRIP_ID}`);

    expect(await screen.findByText('No requests are waiting for your answer.')).toBeDefined();
    expect(queries[0]?.get('tripId')).toBe(TRIP_ID);
    expect(screen.getByRole('link', { name: 'Show every trip' })).toBeDefined();

    cleanup();
    renderAt('/trips/requests?trip=1%20OR%201=1');
    await waitFor(() => {
      expect(queries).toHaveLength(2);
    });
    expect(queries[1]?.has('tripId')).toBe(false);
  });

  it('switches the status filter', async () => {
    const queries = inboxReturning([]);
    signIn();
    renderAt('/trips/requests');
    await screen.findByText('No requests are waiting for your answer.');

    fireEvent.change(screen.getByLabelText('Show'), { target: { value: 'ALL' } });

    expect(await screen.findByText('No requests match this filter.')).toBeDefined();
    expect(queries.at(-1)?.has('status')).toBe(false);
  });

  it('shows the caller their own requests and where each stands', async () => {
    server.use(
      apiGet('/join-requests/outgoing', () =>
        HttpResponse.json(
          pageOf([
            outgoingRequest(),
            outgoingRequest({ id: OTHER_REQUEST_ID, status: 'REJECTED', trip: { ...outgoingRequest().trip, title: 'Desert nights' } }),
          ]),
        ),
      ),
    );
    signIn();
    renderAt('/trips/requests?view=sent');

    expect(await screen.findByText('Waiting for the host')).toBeDefined();
    expect(screen.getByText('Not accepted')).toBeDefined();
    expect(screen.getByRole('tab', { name: 'Sent by me' }).getAttribute('aria-selected')).toBe('true');
  });

  it('invites a search when the caller has asked for nothing yet', async () => {
    server.use(apiGet('/join-requests/outgoing', () => HttpResponse.json(pageOf([]))));
    signIn();
    renderAt('/trips/requests?view=sent');

    expect(await screen.findByText(/You have not asked to join any trips yet/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Find a trip' }).getAttribute('href')).toBe('/trips');
  });

  it('shows an inbox load failure and retries on request', async () => {
    let attempts = 0;
    server.use(
      apiGet('/join-requests/incoming', () => {
        attempts++;
        return attempts === 1
          ? problem(503, 'temporarily_unavailable', 'This action is temporarily unavailable. Try again shortly.')
          : HttpResponse.json(pageOf([incomingRequest()]));
      }),
    );
    signIn();
    renderAt('/trips/requests');

    expect((await screen.findByRole('alert')).textContent).toContain('temporarily unavailable');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Ravi (selfie verified)')).toBeDefined();
  });
});
