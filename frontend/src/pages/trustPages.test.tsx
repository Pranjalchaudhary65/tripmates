// @vitest-environment jsdom
import { cleanup, fireEvent, screen } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiDelete, apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { ASHA_ID, BLOCK_ID, myReviews, TRIP_ID, trustScore, tripReviews } from '../test/trustFixtures';

const server = createApiServer();
const REVIEWS = `/trips/${TRIP_ID}/reviews`;

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

describe('trust score page', () => {
  it('shows each part with its weight and points, and the deductions in words', async () => {
    server.use(apiGet('/users/me/trust-score', () => HttpResponse.json(trustScore())));
    signIn();
    renderAt('/account/trust-score');

    expect(await screen.findByLabelText('Score 57.26 out of 100')).toBeDefined();
    expect(screen.getByText(/Phone verified \(25 points\)/)).toBeDefined();
    expect(screen.getByText('Did not turn up to a confirmed meet-up')).toBeDefined();
    expect(screen.getByText(/Pressing SOS, missing a check-in/)).toBeDefined();
  });

  it('says the score is being worked out while pending', async () => {
    server.use(
      apiGet('/users/me/trust-score', () =>
        HttpResponse.json({ pending: true, score: null, computedAt: null, breakdown: null }),
      ),
    );
    signIn();
    renderAt('/account/trust-score');
    expect(await screen.findByText(/Your score is being calculated/)).toBeDefined();
  });

  it('shows a generic error, not the response, when the breakdown does not add up', async () => {
    server.use(apiGet('/users/me/trust-score', () => HttpResponse.json(trustScore({ score: 99 }))));
    signIn();
    renderAt('/account/trust-score');
    expect(await screen.findByRole('alert')).toBeDefined();
    expect(screen.queryByText(/99/)).toBeNull();
  });
});

describe('trip reviews page', () => {
  it('needs a rating before sending, then shows the review as hidden until they review back', async () => {
    const sent: Record<string, unknown>[] = [];
    server.use(
      apiGet(REVIEWS, () => HttpResponse.json(tripReviews())),
      apiPost(REVIEWS, async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json(
          tripReviews({ status: 'WAITING_FOR_THEM', mine: { rating: 4, comment: 'Good company', at: '2026-11-22T10:00:00Z' } }),
          { status: 201 },
        );
      }),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}/reviews`);
    await screen.findByRole('heading', { level: 1, name: 'Reviews: Monsoon weekend in Gokarna' });

    fireEvent.click(screen.getByRole('button', { name: 'Send review of Asha' }));
    expect(await screen.findByText('Choose from 1 to 5 stars.')).toBeDefined();
    expect(sent).toHaveLength(0);

    fireEvent.click(screen.getByLabelText('4 ★'));
    fireEvent.change(screen.getByLabelText('A few words (optional)'), { target: { value: '  Good company  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send review of Asha' }));

    expect(await screen.findByText(/It stays hidden until they review you/)).toBeDefined();
    expect(sent).toEqual([{ revieweeId: ASHA_ID, rating: 4, comment: 'Good company' }]);
  });

  it('shows the server wording when the review was already sent elsewhere', async () => {
    server.use(
      apiGet(REVIEWS, () => HttpResponse.json(tripReviews())),
      apiPost(REVIEWS, () =>
        problem(409, 'already_reviewed', 'You have already reviewed this person for this trip. Reviews cannot be changed.'),
      ),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}/reviews`);
    await screen.findByRole('heading', { level: 1, name: 'Reviews: Monsoon weekend in Gokarna' });
    fireEvent.click(screen.getByLabelText('5 ★'));
    fireEvent.click(screen.getByRole('button', { name: 'Send review of Asha' }));
    expect(await screen.findByText(/You have already reviewed this person/)).toBeDefined();
  });

  it('answers a trip the user did not travel on as not available, and a bad ID without a request', async () => {
    server.use(apiGet(REVIEWS, () => problem(404, 'not_found', 'Not found.')));
    signIn();
    renderAt(`/trips/${TRIP_ID}/reviews`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Reviews not available' })).toBeDefined();
    cleanup();
    renderAt('/trips/not-a-uuid/reviews');
    expect(await screen.findByRole('heading', { level: 1, name: 'Reviews not available' })).toBeDefined();
  });

  it('reports someone only after a reason is chosen, and says nothing about the outcome', async () => {
    const reports: Record<string, unknown>[] = [];
    server.use(
      apiGet(REVIEWS, () => HttpResponse.json(tripReviews())),
      apiPost('/reports', async ({ request }) => {
        reports.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json({ id: BLOCK_ID, reason: 'HARASSMENT', receivedAt: '2026-11-25T10:00:00Z' }, { status: 201 });
      }),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}/reviews`);
    fireEvent.click(await screen.findByRole('button', { name: 'Report or block Asha' }));
    fireEvent.click(screen.getByRole('button', { name: 'Report Asha' }));
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByText('Choose a reason.')).toBeDefined();
    expect(reports).toHaveLength(0);

    fireEvent.change(screen.getByLabelText('What happened?'), { target: { value: 'HARASSMENT' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send report' }));

    expect(await screen.findByText(/A moderator will look at it/)).toBeDefined();
    expect(reports).toEqual([{ tripId: TRIP_ID, userId: ASHA_ID, reason: 'HARASSMENT', details: null }]);
  });

  it('blocks only after a confirm step', async () => {
    const blocks: Record<string, unknown>[] = [];
    server.use(
      apiGet(REVIEWS, () => HttpResponse.json(tripReviews())),
      apiPost('/blocks', async ({ request }) => {
        blocks.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json({ id: BLOCK_ID, firstName: 'Asha', since: '2026-11-25T10:00:00Z' }, { status: 201 });
      }),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}/reviews`);
    fireEvent.click(await screen.findByRole('button', { name: 'Report or block Asha' }));
    fireEvent.click(screen.getByRole('button', { name: 'Block Asha' }));
    expect(blocks).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Block Asha' }));
    expect(await screen.findByText(/Asha is blocked/)).toBeDefined();
    expect(blocks).toEqual([{ tripId: TRIP_ID, userId: ASHA_ID }]);
  });
});

describe('reviews inbox and blocked people', () => {
  it('lists trips waiting for a review', async () => {
    server.use(apiGet('/users/me/reviews', () => HttpResponse.json(myReviews())));
    signIn();
    renderAt('/reviews');
    const link = await screen.findByRole('link', { name: 'Monsoon weekend in Gokarna' });
    expect(link.getAttribute('href')).toBe(`/trips/${TRIP_ID}/reviews`);
    expect(screen.getByText(/1 person to review/)).toBeDefined();
  });

  it('lifts a block and reloads the list', async () => {
    let blocked = true;
    server.use(
      apiGet('/blocks', () =>
        HttpResponse.json({ items: blocked ? [{ id: BLOCK_ID, firstName: 'Asha', since: '2026-11-25T10:00:00Z' }] : [] }),
      ),
      apiDelete(`/blocks/${BLOCK_ID}`, () => {
        blocked = false;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    signIn();
    renderAt('/account/blocked');
    fireEvent.click(await screen.findByRole('button', { name: 'Unblock' }));
    expect(await screen.findByText(/haven't blocked anyone/)).toBeDefined();
  });
});
