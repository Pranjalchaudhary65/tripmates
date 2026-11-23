// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore, type VerificationTier } from '../stores/authStore';
import { apiDelete, apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { heading, installSessionHandling, renderAt, signIn } from '../test/renderApp';

const FRIEND_ID = '5e0c8b7a-1d2e-4f3a-9b8c-7d6e5f4a3b2c';
const OTHER_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';

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

function vouch(id: string, firstName: string, vouchedUserId = FRIEND_ID) {
  return { id, vouchedUserId, vouchedFirstName: firstName, since: '2026-11-20T08:00:00Z' };
}

function signInAt(tier: VerificationTier) {
  signIn();
  useAuthStore.getState().setVerificationTier(tier);
}

async function openVouches(given: ReturnType<typeof vouch>[] = [], receivedCount = 0) {
  server.use(apiGet('/vouches', () => HttpResponse.json({ canVouch: true, limit: 5, given, receivedCount })));
  signInAt('SELFIE_MATCH');
  renderAt('/account/vouches');
  await screen.findByText(/You vouch for/);
}

describe('vouch screen guard', () => {
  it.each(['EMAIL', 'PHONE'] as const)('explains what is missing at %s instead of showing the screen', async (tier) => {
    let calls = 0;
    server.use(
      apiGet('/vouches', () => {
        calls++;
        return HttpResponse.json({ canVouch: false, limit: 5, given: [], receivedCount: 0 });
      }),
    );
    signInAt(tier);

    renderAt('/account/vouches');

    expect(heading()).toBe('Not available yet');
    expect(screen.getByText('This needs the selfie check. It takes a minute or two.')).toBeDefined();
    expect(screen.getByRole('link', { name: 'Go to the verification centre' }).getAttribute('href')).toBe(
      '/account/verification',
    );
    await waitFor(() => {
      expect(calls).toBe(0);
    });
  });

  it('sends a signed-out visitor to login first', async () => {
    renderAt('/account/vouches');

    expect(await screen.findByRole('heading', { level: 1, name: 'Log in' })).toBeDefined();
  });
});

describe('vouch screen', () => {
  it('lists active vouches by first name with what is left of the limit', async () => {
    await openVouches([vouch('v-1', 'Asha'), vouch('v-2', 'Ravi', OTHER_ID)], 3);

    expect(screen.getByText('You vouch for 2 of 5 people. 3 vouch for you.')).toBeDefined();
    expect(screen.getAllByRole('listitem').map((item) => within(item).getByText(/Asha|Ravi/).textContent)).toEqual([
      'Asha',
      'Ravi',
    ]);
  });

  it('shows an empty list plainly', async () => {
    await openVouches();

    expect(screen.getByText('You are not vouching for anyone yet.')).toBeDefined();
  });

  it('checks the ID shape before sending it', async () => {
    let calls = 0;
    server.use(
      apiPost('/vouches', () => {
        calls++;
        return HttpResponse.json(vouch('v-9', 'Asha'), { status: 201 });
      }),
    );
    await openVouches();

    fireEvent.click(screen.getByRole('button', { name: 'Vouch' }));
    expect(await screen.findByText("Paste your friend's TripMates ID.")).toBeDefined();
    fireEvent.change(screen.getByLabelText("Your friend's TripMates ID"), { target: { value: 'asha@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Vouch' }));
    expect(await screen.findByText(/A TripMates ID looks like/)).toBeDefined();
    expect(calls).toBe(0);
  });

  it('vouches and adds the friend to the list', async () => {
    let body: unknown = null;
    server.use(
      apiPost('/vouches', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(vouch('v-9', 'Asha'), { status: 201 });
      }),
    );
    await openVouches();

    fireEvent.change(screen.getByLabelText("Your friend's TripMates ID"), {
      target: { value: ` ${FRIEND_ID.toUpperCase()} ` },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Vouch' }));

    expect(await screen.findByText('Asha')).toBeDefined();
    expect(body).toEqual({ vouchedUserId: FRIEND_ID });
    expect(screen.getByLabelText("Your friend's TripMates ID")).toHaveProperty('value', '');
  });

  it.each([
    ['not_found', 404, 'The requested resource was not found.', 'That could not be found.'],
    ['already_vouching', 409, 'You already vouch for this person.', 'You already vouch for this person.'],
    [
      'vouch_limit_reached',
      409,
      'You are vouching for as many people as you can. Withdraw a vouch to vouch for someone new.',
      'You are vouching for as many people as you can.',
    ],
    [
      'vouch_requires_selfie_match',
      409,
      'Complete the selfie check before vouching for someone.',
      'Complete the selfie check before vouching for someone.',
    ],
  ])('shows %s from the server', async (code, status, detail, shown) => {
    server.use(apiPost('/vouches', () => problem(status, code, detail)));
    await openVouches();

    fireEvent.change(screen.getByLabelText("Your friend's TripMates ID"), { target: { value: FRIEND_ID } });
    fireEvent.click(screen.getByRole('button', { name: 'Vouch' }));

    expect((await screen.findByRole('alert')).textContent).toContain(shown);
  });

  it('puts a self-vouch refusal beside the ID field', async () => {
    const detail = 'You cannot vouch for yourself.';
    server.use(
      apiPost('/vouches', () =>
        HttpResponse.json(
          { status: 400, code: 'cannot_vouch_for_self', detail, errors: [{ field: 'vouchedUserId', message: detail }] },
          { status: 400 },
        ),
      ),
    );
    await openVouches();

    fireEvent.change(screen.getByLabelText("Your friend's TripMates ID"), { target: { value: FRIEND_ID } });
    fireEvent.click(screen.getByRole('button', { name: 'Vouch' }));

    expect(await screen.findByText(detail)).toBeDefined();
    expect(screen.getByLabelText("Your friend's TripMates ID").getAttribute('aria-invalid')).toBe('true');
  });

  it('hides the form at the limit', async () => {
    await openVouches(['a', 'b', 'c', 'd', 'e'].map((id) => vouch(id, `Friend ${id}`)));

    expect(screen.queryByLabelText("Your friend's TripMates ID")).toBeNull();
    expect(screen.getByText('Withdraw a vouch to vouch for someone new.')).toBeDefined();
  });

  it('withdraws a vouch and drops it from the list', async () => {
    let deleted = '';
    server.use(
      apiDelete('/vouches/:id', ({ params }) => {
        deleted = String(params['id']);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await openVouches([vouch('v-1', 'Asha'), vouch('v-2', 'Ravi', OTHER_ID)]);

    fireEvent.click(screen.getByRole('button', { name: 'Withdraw your vouch for Asha' }));

    await waitFor(() => {
      expect(screen.queryByText('Asha')).toBeNull();
    });
    expect(deleted).toBe('v-1');
    expect(screen.getByText('Ravi')).toBeDefined();
  });

  it('keeps a vouch whose withdrawal failed, and says why', async () => {
    server.use(apiDelete('/vouches/:id', () => problem(404, 'not_found', 'The requested resource was not found.')));
    await openVouches([vouch('v-1', 'Asha')]);

    fireEvent.click(screen.getByRole('button', { name: 'Withdraw your vouch for Asha' }));

    expect((await screen.findByRole('alert')).textContent).toContain('That could not be found.');
    expect(screen.getByText('Asha')).toBeDefined();
  });

  it('renders a first name as text, never as markup', async () => {
    await openVouches([vouch('v-1', '<img src=x onerror=alert(1)>')]);

    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeDefined();
    expect(document.querySelector('img')).toBeNull();
  });

  it('shows a load failure and retries on request', async () => {
    let attempts = 0;
    server.use(
      apiGet('/vouches', () => {
        attempts++;
        return attempts === 1
          ? HttpResponse.error()
          : HttpResponse.json({ canVouch: true, limit: 5, given: [], receivedCount: 0 });
      }),
    );
    signInAt('SELFIE_MATCH');
    renderAt('/account/vouches');

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('You are not vouching for anyone yet.')).toBeDefined();
  });
});
