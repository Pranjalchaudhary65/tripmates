// @vitest-environment jsdom
import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import {
  blockedMatch,
  pageOf,
  scoredMatch,
  TRIP_ID,
  tripDetail,
  tripSummary,
  unscoredMatch,
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

async function openTrip(detail: ReturnType<typeof tripDetail>) {
  server.use(apiGet(`/trips/${TRIP_ID}`, () => HttpResponse.json(detail)));
  signIn();
  renderAt(`/trips/${TRIP_ID}`);
  await screen.findByRole('heading', { level: 1, name: 'Monsoon weekend in Gokarna' });
}

function breakdown(): HTMLElement {
  return screen.getByRole('region', { name: 'How well you match' });
}

describe('trip match breakdown', () => {
  it('shows the score and what fits and differs, each under its dimension', async () => {
    await openTrip(tripDetail({ matchScore: 82 }, { match: scoredMatch(82) }));

    const region = breakdown();
    expect(within(region).getByText('82%')).toBeDefined();
    expect(within(region).getByRole('meter', { name: 'Match score' }).getAttribute('aria-valuenow')).toBe('82');
    const fits = within(region).getByRole('heading', { level: 3, name: 'What fits' }).parentElement;
    const differs = within(region).getByRole('heading', { level: 3, name: 'What differs' }).parentElement;
    expect(fits?.textContent).toContain('You are both early birds.');
    expect(fits?.textContent).toContain('Mornings');
    expect(differs?.textContent).toContain('You like a detailed itinerary; the host likes no fixed plan.');
    expect(screen.getByRole('button', { name: 'Ask to join' })).toBeDefined();
  });

  it('says so plainly when nothing lines up or nothing differs', async () => {
    await openTrip(tripDetail({}, { match: { ...scoredMatch(50), matches: [], mismatches: [] } }));

    expect(within(breakdown()).getByText('Nothing lines up closely.')).toBeDefined();
    expect(within(breakdown()).getByText('Nothing differs sharply.')).toBeDefined();
  });

  it('explains a deal-breaker and offers no join button', async () => {
    await openTrip(tripDetail({}, { match: blockedMatch() }));

    const region = breakdown();
    expect(within(region).getByText('Not a match: a deal-breaker rules this trip out.')).toBeDefined();
    expect(within(region).getByText('The host is travelling only with people of their own gender.')).toBeDefined();
    expect(within(region).getByRole('link', { name: 'Review your deal-breakers' }).getAttribute('href')).toBe('/vibe');
    expect(screen.queryByRole('button', { name: 'Ask to join' })).toBeNull();
    expect(within(region).queryByRole('meter')).toBeNull();
  });

  it('sends a traveller without a vibe to the quiz', async () => {
    await openTrip(tripDetail({}, { match: unscoredMatch('NEEDS_YOUR_PROFILE') }));

    expect(within(breakdown()).getByRole('link', { name: 'Take the quiz' }).getAttribute('href')).toBe('/vibe/quiz');
    expect(screen.getByRole('button', { name: 'Ask to join' })).toBeDefined();
  });

  it('says when the host has nothing to compare with', async () => {
    await openTrip(tripDetail({}, { match: unscoredMatch('HOST_HAS_NO_PROFILE') }));

    expect(within(breakdown()).getByText(/The host has not taken the vibe quiz yet/)).toBeDefined();
  });

  it('shows no breakdown on the host’s own trip', async () => {
    await openTrip(tripDetail({ hostedByMe: true, pendingRequests: 0 }));

    expect(screen.queryByRole('region', { name: 'How well you match' })).toBeNull();
  });

  it('renders a reason as text, never as markup', async () => {
    const match = { ...scoredMatch(), matches: [{ dimension: 'FOOD', label: '<img src=x onerror=alert(1)>' }] };
    await openTrip(tripDetail({}, { match }));

    expect(within(breakdown()).getByText('<img src=x onerror=alert(1)>')).toBeDefined();
    expect(document.querySelector('img')).toBeNull();
  });

  it('explains a deal-breaker conflict that only shows up on joining', async () => {
    const detail = "Your deal-breakers and this trip's host's do not fit together, so you cannot ask to join it.";
    server.use(apiPost(`/trips/${TRIP_ID}/join-requests`, () => problem(409, 'deal_breaker_conflict', detail)));
    await openTrip(tripDetail({}, { match: scoredMatch() }));

    fireEvent.click(screen.getByRole('button', { name: 'Ask to join' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
  });

  it('treats a match that contradicts itself as a broken response, not something to half-render', async () => {
    server.use(
      apiGet(`/trips/${TRIP_ID}`, () =>
        HttpResponse.json(tripDetail({}, { match: { ...blockedMatch(), status: 'SCORED' } })),
      ),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}`);

    expect((await screen.findByRole('alert')).textContent).toContain('Something went wrong');
    expect(screen.queryByRole('region', { name: 'How well you match' })).toBeNull();
  });

  it('shows the score on browse cards that have one', async () => {
    server.use(
      apiGet('/trips', () =>
        HttpResponse.json(
          pageOf([tripSummary({ matchScore: 64 }), tripSummary({ id: 'f'.repeat(8) + TRIP_ID.slice(8), title: 'Unscored' })]),
        ),
      ),
    );
    signIn();
    renderAt('/trips');

    expect(await screen.findByText('64% match')).toBeDefined();
    const unscored = screen.getByRole('link', { name: 'Unscored' }).closest('li');
    expect(unscored?.textContent).not.toContain('% match');
  });
});
