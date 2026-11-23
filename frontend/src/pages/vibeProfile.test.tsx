// @vitest-environment jsdom
import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiGet, apiPost, apiPut, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { profileBody } from '../test/vibeFixtures';

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

async function openProfile(options: Parameters<typeof profileBody>[0] = {}) {
  server.use(apiGet('/vibe/profile', () => HttpResponse.json(profileBody(options))));
  signIn();
  renderAt('/vibe');
  await screen.findByRole('heading', { level: 2, name: 'Deal-breakers' });
}

function section(name: string): HTMLElement {
  const element = screen.getByRole('heading', { level: 2, name }).closest('section');
  if (element === null) {
    throw new Error(`No section ${name}`);
  }
  return element;
}

describe('vibe profile', () => {
  it('needs a session', async () => {
    renderAt('/vibe');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeDefined();
  });

  it('offers the quiz when there is no vibe yet, and still lets deal-breakers be set', async () => {
    await openProfile({ profile: false });

    expect(screen.getByText('You have not taken the vibe quiz yet.')).toBeDefined();
    expect(screen.getByRole('link', { name: 'Take the quiz' }).getAttribute('href')).toBe('/vibe/quiz');
    expect(screen.getByRole('button', { name: 'Save deal-breakers' })).toBeDefined();
  });

  it('shows each dimension in words', async () => {
    await openProfile();

    const style = section('Travel style');
    expect(within(style).getByText('Mid-range')).toBeDefined();
    expect(within(style).getByText('Early bird')).toBeDefined();
    expect(within(style).getByText('4 of 5')).toBeDefined();
    expect(within(style).getByText('Whoever can pays, it evens out')).toBeDefined();
    expect(within(style).queryByText(/adjusted this by hand/)).toBeNull();
    expect(within(style).getByRole('link', { name: 'Retake the quiz' }).getAttribute('href')).toBe('/vibe/quiz');
  });

  it('says when the vibe was adjusted by hand', async () => {
    await openProfile({ adjusted: true });

    expect(screen.getByText('You adjusted this by hand after the quiz.')).toBeDefined();
  });

  it('adjusts every dimension in one save', async () => {
    let body: unknown = null;
    server.use(
      apiPut('/vibe/profile', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          ...profileBody({ adjusted: true }),
          profile: { ...profileBody({ adjusted: true }).profile, budgetBand: 'LUXURY', riskAppetite: 5 },
        });
      }),
    );
    await openProfile();

    fireEvent.click(screen.getByRole('button', { name: 'Adjust' }));
    fireEvent.change(screen.getByLabelText('Budget'), { target: { value: 'LUXURY' } });
    fireEvent.change(screen.getByLabelText(/Appetite for risk: 2 of 5/), { target: { value: '5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save my vibe' }));

    expect(await within(section('Travel style')).findByText('Luxury')).toBeDefined();
    expect(body).toEqual({
      budgetBand: 'LUXURY',
      pace: 'BALANCED',
      wakeTime: 'EARLY_BIRD',
      planningStyle: 'ROUGH_PLAN',
      socialEnergy: 4,
      riskAppetite: 5,
      foodAdventurousness: 5,
      expenseSplitStyle: 'WHOEVER_CAN',
    });
    expect(screen.getByText('You adjusted this by hand after the quiz.')).toBeDefined();
  });

  it('places a refused dimension beside its control', async () => {
    server.use(
      apiPut('/vibe/profile', () =>
        HttpResponse.json(
          { status: 400, detail: 'The request is malformed or failed validation.', errors: [{ field: 'socialEnergy', message: 'must be less than or equal to 5' }] },
          { status: 400 },
        ),
      ),
    );
    await openProfile();

    fireEvent.click(screen.getByRole('button', { name: 'Adjust' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save my vibe' }));

    expect(await screen.findByText('must be less than or equal to 5')).toBeDefined();
    expect(screen.getByLabelText(/Social energy/).getAttribute('aria-invalid')).toBe('true');
  });

  it('explains when there is no vibe left to adjust', async () => {
    const detail = 'Take the vibe quiz first. You can adjust the result afterwards.';
    server.use(apiPut('/vibe/profile', () => problem(409, 'vibe_profile_missing', detail)));
    await openProfile();

    fireEvent.click(screen.getByRole('button', { name: 'Adjust' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save my vibe' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
  });

  it('cancels an adjustment without saving', async () => {
    await openProfile();

    fireEvent.click(screen.getByRole('button', { name: 'Adjust' }));
    fireEvent.change(screen.getByLabelText('Budget'), { target: { value: 'LUXURY' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(within(section('Travel style')).getByText('Mid-range')).toBeDefined();
  });
});

describe('deal-breakers', () => {
  it('starts from the saved deal-breakers', async () => {
    await openProfile({ dealBreakers: true });

    expect(screen.getByLabelText('Smoking')).toHaveProperty('value', 'NO');
    expect(screen.getByLabelText('Travel with')).toHaveProperty('value', 'SAME');
    expect(screen.getByLabelText('Lowest trust score I will match with')).toHaveProperty('value', '40');
  });

  it('saves all four together and confirms it', async () => {
    let body: unknown = null;
    server.use(
      apiPut('/vibe/deal-breakers', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(profileBody({ dealBreakers: true }));
      }),
    );
    await openProfile();

    fireEvent.change(screen.getByLabelText('Smoking'), { target: { value: 'NO' } });
    fireEvent.change(screen.getByLabelText('Travel with'), { target: { value: 'SAME' } });
    fireEvent.change(screen.getByLabelText('Lowest trust score I will match with'), { target: { value: '40' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save deal-breakers' }));

    expect(await screen.findByText('Deal-breakers saved.')).toBeDefined();
    expect(body).toEqual({ smoking: 'NO', drinking: 'EITHER', genderPreference: 'SAME', minTrustScore: 40 });
  });

  it.each(['', '101', '-1', '2.5'])('checks a trust score of %j before sending it', async (value) => {
    let calls = 0;
    server.use(
      apiPut('/vibe/deal-breakers', () => {
        calls++;
        return HttpResponse.json(profileBody({ dealBreakers: true }));
      }),
    );
    await openProfile();

    fireEvent.change(screen.getByLabelText('Lowest trust score I will match with'), { target: { value } });
    fireEvent.click(screen.getByRole('button', { name: 'Save deal-breakers' }));

    expect(await screen.findByText('Enter a whole number from 0 to 100.')).toBeDefined();
    expect(calls).toBe(0);
  });

  it('places a server refusal beside its field', async () => {
    server.use(
      apiPut('/vibe/deal-breakers', () =>
        HttpResponse.json(
          { status: 400, detail: 'The request is malformed or failed validation.', errors: [{ field: 'minTrustScore', message: 'must be less than or equal to 100' }] },
          { status: 400 },
        ),
      ),
    );
    await openProfile();

    fireEvent.click(screen.getByRole('button', { name: 'Save deal-breakers' }));

    expect(await screen.findByText('must be less than or equal to 100')).toBeDefined();
  });

  it('shows a rate limit with its wait', async () => {
    server.use(
      apiPut('/vibe/deal-breakers', () =>
        problem(429, 'rate_limited', 'Too many attempts.', { headers: { 'Retry-After': '120' } }),
      ),
    );
    await openProfile();

    fireEvent.click(screen.getByRole('button', { name: 'Save deal-breakers' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Too many attempts.');
    expect(screen.getByRole('button', { name: /Try again in \d+s/ })).toHaveProperty('disabled', true);
  });
});

describe('loading', () => {
  it('shows a failure and retries', async () => {
    let attempts = 0;
    server.use(
      apiGet('/vibe/profile', () => {
        attempts++;
        return attempts === 1 ? HttpResponse.error() : HttpResponse.json(profileBody());
      }),
    );
    signIn();
    renderAt('/vibe');

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Mid-range')).toBeDefined();
  });

  it('treats a profile it cannot read as a failure, not a half-drawn screen', async () => {
    server.use(
      apiGet('/vibe/profile', () =>
        HttpResponse.json({ ...profileBody(), profile: { ...profileBody().profile, socialEnergy: 9 } }),
      ),
    );
    signIn();
    renderAt('/vibe');

    expect((await screen.findByRole('alert')).textContent).toContain('Something went wrong.');
  });
});
