// @vitest-environment jsdom
import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiPost, authenticated, createApiServer, problem, secondFactorRequired } from '../test/apiServer';
import { heading, installSessionHandling, renderAt, SIGNED_IN_USER_ID } from '../test/renderApp';

const CHALLENGE_ID = 'Zm9yLXRlc3RzLW9ubHktbm90LWEtcmVhbC1jaGFsbGVuZ2U';
const INVALID_SECOND_FACTOR =
  'That code is not valid. Enter the current code from your authenticator app, or an unused backup code.';
const CHALLENGE_EXPIRED = 'This sign-in has expired. Enter your email and password again.';

const server = createApiServer();
const refreshCalls: Request[] = [];
let secondFactorBodies: unknown[] = [];

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  refreshCalls.length = 0;
  secondFactorBodies = [];
  server.use(
    apiPost(REFRESH_PATH, ({ request }) => {
      refreshCalls.push(request);
      return problem(401, 'invalid_refresh_token', 'Your session has ended.');
    }),
    apiPost('/auth/login', () => HttpResponse.json(secondFactorRequired(CHALLENGE_ID))),
  );
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function answerSecondFactor(respond: () => Response) {
  server.use(
    apiPost('/auth/login/second-factor', async ({ request }) => {
      secondFactorBodies.push(await request.json());
      return respond();
    }),
  );
}

/** Logs in from the login screen, as a 2FA user would, and lands on the code screen. */
async function reachSecondFactor(from?: string) {
  const router = renderAt('/login', from === undefined ? undefined : { from });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'asha@example.com' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'violet-kettle-harbour-91' } });
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }));
  await screen.findByLabelText('Authenticator code');
  expect(router.state.location.pathname).toBe('/login/second-factor');
  return router;
}

function submitCode(code: string, label = 'Authenticator code') {
  fireEvent.change(screen.getByLabelText(label), { target: { value: code } });
  fireEvent.click(screen.getByRole('button', { name: 'Verify' }));
}

describe('second-factor screen', () => {
  it('goes from the password to the code screen without a session or a token', async () => {
    await reachSecondFactor();

    expect(heading()).toBe('Two-factor authentication');
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(screen.getByText(/This sign-in expires in 5:00\./)).toBeDefined();
  });

  it('signs in with a valid code and returns to the page the user was headed for', async () => {
    answerSecondFactor(() => HttpResponse.json(authenticated(SIGNED_IN_USER_ID, 'EMAIL', { amr: ['pwd', 'otp'] })));
    const router = await reachSecondFactor('/account/security');

    submitCode('492039');

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/account/security');
    });
    expect(secondFactorBodies).toEqual([{ challengeId: CHALLENGE_ID, code: '492039' }]);
    expect(useAuthStore.getState().authMethods).toEqual(['pwd', 'otp']);
  });

  it('accepts a backup code in the other mode, sent as typed', async () => {
    answerSecondFactor(() => HttpResponse.json(authenticated(SIGNED_IN_USER_ID, 'EMAIL', { amr: ['pwd', 'otp'] })));
    const router = await reachSecondFactor();

    fireEvent.click(screen.getByRole('button', { name: 'Use a backup code instead' }));
    submitCode('7kq2m-x9pda', 'Backup code');

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/');
    });
    expect(secondFactorBodies).toEqual([{ challengeId: CHALLENGE_ID, code: '7kq2m-x9pda' }]);
  });

  it('checks the shape of the code before sending anything', async () => {
    await reachSecondFactor();

    submitCode('12345');

    expect(await screen.findByText('Enter the 6-digit code from your authenticator app.')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Use a backup code instead' }));
    submitCode('not-a-code', 'Backup code');
    expect(await screen.findByText(/Enter one of your backup codes/)).toBeDefined();
    expect(secondFactorBodies).toHaveLength(0);
  });

  it('shows a wrong code as the server words it, clears the input and keeps the challenge', async () => {
    answerSecondFactor(() => problem(401, 'invalid_second_factor', INVALID_SECOND_FACTOR));
    await reachSecondFactor();

    submitCode('000000');

    expect((await screen.findByRole('alert')).textContent).toContain(INVALID_SECOND_FACTOR);
    expect(screen.getByLabelText<HTMLInputElement>('Authenticator code').value).toBe('');
    expect(useAuthStore.getState().pendingSecondFactor?.challengeId).toBe(CHALLENGE_ID);
    expect(refreshCalls).toHaveLength(0);
  });

  it('explains an expired challenge and leads back to the password', async () => {
    answerSecondFactor(() => problem(401, 'login_challenge_expired', CHALLENGE_EXPIRED));
    const router = await reachSecondFactor();

    submitCode('123456');

    expect((await screen.findByRole('alert')).textContent).toBe(CHALLENGE_EXPIRED);
    expect(screen.queryByLabelText('Authenticator code')).toBeNull();
    fireEvent.click(screen.getByRole('link', { name: 'Back to log in' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(useAuthStore.getState().pendingSecondFactor).toBeNull();
    expect(refreshCalls).toHaveLength(0);
  });

  it('counts down on a 429 and refuses to send until it ends', async () => {
    answerSecondFactor(() =>
      problem(429, 'rate_limited', 'Wait for the number of seconds in the Retry-After header.', {
        headers: { 'Retry-After': '42' },
      }),
    );
    await reachSecondFactor();

    submitCode('123456');

    const waiting = await screen.findByRole('button', { name: 'Try again in 42s' });
    expect(waiting.hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain('Too many attempts.');
    expect(document.body.textContent).not.toContain('Retry-After header');
    fireEvent.submit(waiting.closest('form') ?? waiting);
    expect(secondFactorBodies).toHaveLength(1);
  });

  it('says the service is unavailable on a 503 and lets the user try again', async () => {
    answerSecondFactor(() => problem(503, 'temporarily_unavailable', 'TripMates is temporarily unavailable.'));
    await reachSecondFactor();

    submitCode('123456');

    expect((await screen.findByRole('alert')).textContent).toContain('TripMates is temporarily unavailable.');
    expect(screen.getByLabelText('Authenticator code')).toBeDefined();
  });

  it('puts a server validation error beside the code', async () => {
    answerSecondFactor(() =>
      HttpResponse.json(
        { status: 400, code: 'validation_failed', detail: 'x', errors: [{ field: 'code', message: 'must not be blank' }] },
        { status: 400 },
      ),
    );
    await reachSecondFactor();

    submitCode('123456');

    expect(await screen.findByText('must not be blank')).toBeDefined();
  });

  it('shows the generic message for an unknown server error, never its body', async () => {
    answerSecondFactor(() =>
      HttpResponse.json(
        { status: 500, detail: 'NullPointerException at SecondFactorLoginService', correlationId: 'corr-500' },
        { status: 500 },
      ),
    );
    await reachSecondFactor();

    submitCode('123456');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Something went wrong on our side.');
    expect(alert.textContent).toContain('corr-500');
    expect(document.body.textContent).not.toContain('NullPointerException');
  });

  it('tells the user when the network is down', async () => {
    answerSecondFactor(() => HttpResponse.error());
    await reachSecondFactor();

    submitCode('123456');

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates.');
  });

  it('expires on screen when the countdown runs out', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    useAuthStore.setState({ pendingSecondFactor: { challengeId: CHALLENGE_ID, expiresAt: Date.now() + 3_000 } });
    renderAt('/login/second-factor');
    expect(screen.getByText(/This sign-in expires in 3 seconds\./)).toBeDefined();

    act(() => {
      vi.advanceTimersByTime(3_500);
    });

    expect(screen.getByRole('alert').textContent).toBe('This sign-in has expired. Enter your email and password again.');
    expect(screen.queryByRole('button', { name: 'Verify' })).toBeNull();
  });

  it('shows the empty state after a reload, since the challenge was only in memory', () => {
    renderAt('/login/second-factor');

    expect(screen.getByText(/There is no sign-in waiting for a code in this tab/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Back to log in' }).getAttribute('href')).toBe('/login');
  });
});
