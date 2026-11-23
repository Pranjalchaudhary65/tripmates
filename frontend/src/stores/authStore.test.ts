// @vitest-environment jsdom
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { installAuthInterceptors, REFRESH_PATH } from '../api/authRefresh';
import { apiClient } from '../api/client';
import type { RegistrationBody } from '../api/identityApi';
import { FALLBACK_RETRY_AFTER_SECONDS } from '../api/problem';
import {
  accessGrant,
  apiPost,
  authenticated,
  createApiServer,
  fakeAccessToken,
  problem,
  secondFactorRequired,
} from '../test/apiServer';
import { provideSessionRefresher, useAuthStore } from './authStore';

const USER = { id: 'u-1', role: 'USER', verificationTier: 'PHONE' } as const;
const CHALLENGE_MESSAGE = 'If this email address can be used, a verification code has been sent to it.';
const FIRST_ID = '5b0c6f1e-6a3e-4c55-9d7a-1f2e3d4c5b6a';
const SECOND_ID = '9e8d7c6b-5a49-4382-a716-1b2c3d4e5f60';
const USER_ID = '0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c';
const CHALLENGE_ID = 'Zm9yLXRlc3RzLW9ubHktbm90LWEtcmVhbC1jaGFsbGVuZ2U';
const INVALID_SECOND_FACTOR =
  'That code is not valid. Enter the current code from your authenticator app, or an unused backup code.';

const REGISTRATION: RegistrationBody = {
  email: 'asha.verma@example.com',
  password: 'violet-kettle-harbour-91',
  fullName: 'Asha Verma',
  dateOfBirth: '2000-04-15',
  gender: 'FEMALE',
};

const server = createApiServer();
const refreshCalls: Request[] = [];

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  // What main.tsx wires up, so these tests see what the app sees.
  provideSessionRefresher(installAuthInterceptors(apiClient, { onSessionExpired: vi.fn() }));
});

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  refreshCalls.length = 0;
  useAuthStore.getState().clearSession();
  useAuthStore.setState({ pendingVerification: null });
  server.use(
    apiPost(REFRESH_PATH, ({ request }) => {
      refreshCalls.push(request);
      return problem(401, 'invalid_refresh_token', 'Your session has ended. Log in again.');
    }),
  );
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

function challenge(verificationId: string) {
  return HttpResponse.json({ verificationId, message: CHALLENGE_MESSAGE }, { status: 202 });
}

function expectNothingInBrowserStorage(...secrets: string[]) {
  expect(localStorage).toHaveLength(0);
  expect(sessionStorage).toHaveLength(0);
  for (const secret of secrets) {
    expect(document.cookie).not.toContain(secret);
    expect(window.location.href).not.toContain(secret);
  }
}

async function startSecondFactor() {
  server.use(apiPost('/auth/login', () => HttpResponse.json(secondFactorRequired(CHALLENGE_ID))));
  const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });
  expect(result).toEqual({ ok: true, next: 'secondFactor' });
}

describe('auth store session state', () => {
  it('starts with no session', () => {
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().pendingSecondFactor).toBeNull();
    expect(useAuthStore.getState().pendingVerification).toBeNull();
  });

  it('holds the session and clears it completely', () => {
    useAuthStore.getState().setSession({ accessToken: 'token-1', user: USER, authMethods: ['pwd', 'otp'] });
    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().accessToken).toBe('token-1');
    expect(useAuthStore.getState().user).toEqual(USER);
    expect(useAuthStore.getState().authMethods).toEqual(['pwd', 'otp']);

    useAuthStore.getState().clearSession('revokedAll');
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().authMethods).toEqual([]);
    expect(useAuthStore.getState().signOutReason).toBe('revokedAll');
  });

  it('never writes the token, the challengeId or the verificationId to browser storage', async () => {
    const token = fakeAccessToken(['pwd', 'otp'], 'secret-token');
    server.use(
      apiPost('/auth/register', () => challenge(FIRST_ID)),
      apiPost('/auth/login/second-factor', () =>
        HttpResponse.json(authenticated(USER_ID, 'EMAIL', { amr: ['pwd', 'otp'], tokenId: 'secret-token' })),
      ),
    );

    await useAuthStore.getState().register(REGISTRATION);
    await startSecondFactor();
    expectNothingInBrowserStorage(FIRST_ID, CHALLENGE_ID);
    await useAuthStore.getState().completeSecondFactor('123456');

    expect(useAuthStore.getState().accessToken).toBe(token);
    expectNothingInBrowserStorage(FIRST_ID, CHALLENGE_ID, token);
  });
});

describe('restoreSession', () => {
  it('restores a reloaded session with one refresh call', async () => {
    server.use(
      apiPost(REFRESH_PATH, ({ request }) => {
        refreshCalls.push(request);
        return HttpResponse.json(accessGrant(USER_ID, 'EMAIL', { amr: ['pwd', 'otp'] }));
      }),
    );
    useAuthStore.setState({ status: 'starting' });

    await useAuthStore.getState().restoreSession();

    expect(refreshCalls).toHaveLength(1);
    expect(refreshCalls[0]?.headers.get('X-TripMates-CSRF')).toBe('1');
    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().user).toEqual({ id: USER_ID, role: 'USER', verificationTier: 'EMAIL' });
    expect(useAuthStore.getState().authMethods).toEqual(['pwd', 'otp']);
  });

  it('settles on signed out, with no notice, when there is no session to restore', async () => {
    useAuthStore.setState({ status: 'starting' });

    await useAuthStore.getState().restoreSession();

    expect(refreshCalls).toHaveLength(1);
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().signOutReason).toBeNull();
  });

  it('waits instead of signing out when the server cannot answer', async () => {
    server.use(
      apiPost(REFRESH_PATH, () =>
        problem(503, 'temporarily_unavailable', 'TripMates is busy.', { headers: { 'Retry-After': '20' } }),
      ),
    );
    useAuthStore.setState({ status: 'starting' });

    await useAuthStore.getState().restoreSession();

    expect(useAuthStore.getState().status).toBe('unavailable');
    expect(useAuthStore.getState().startupRetryAfterSeconds).toBe(20);
  });
});

describe('register', () => {
  it('posts the form and keeps the verificationId in memory', async () => {
    const bodies: unknown[] = [];
    server.use(
      apiPost('/auth/register', async ({ request }) => {
        bodies.push(await request.json());
        return challenge(FIRST_ID);
      }),
    );

    const result = await useAuthStore.getState().register(REGISTRATION);

    expect(result).toEqual({ ok: true });
    expect(bodies).toEqual([REGISTRATION]);
    expect(useAuthStore.getState().pendingVerification).toMatchObject({
      verificationId: FIRST_ID,
      email: REGISTRATION.email,
      message: CHALLENGE_MESSAGE,
    });
  });

  it('returns field errors from a 400 and stores nothing', async () => {
    server.use(
      apiPost('/auth/register', () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'password_breached',
            detail: 'This password has appeared in a known data breach. Choose a different one.',
            errors: [
              {
                field: 'password',
                message: 'This password has appeared in a known data breach. Choose a different one.',
              },
            ],
          },
          { status: 400 },
        ),
      ),
    );

    const result = await useAuthStore.getState().register(REGISTRATION);

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.failure.code).toBe('password_breached');
      expect(result.failure.fieldErrors['password']).toEqual([
        'This password has appeared in a known data breach. Choose a different one.',
      ]);
    }
    expect(useAuthStore.getState().pendingVerification).toBeNull();
  });

  it('treats a 202 without a verificationId as a failure rather than storing undefined', async () => {
    server.use(apiPost('/auth/register', () => HttpResponse.json({ message: 'ok' }, { status: 202 })));

    const result = await useAuthStore.getState().register(REGISTRATION);

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().pendingVerification).toBeNull();
  });
});

describe('verifyEmail and resend', () => {
  beforeEach(async () => {
    server.use(apiPost('/auth/register', () => challenge(FIRST_ID)));
    await useAuthStore.getState().register(REGISTRATION);
  });

  it('pairs the code with the stored verificationId and clears it on success', async () => {
    const bodies: unknown[] = [];
    server.use(
      apiPost('/auth/verify-email', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ emailVerified: true, verificationTier: 'EMAIL' });
      }),
    );

    const result = await useAuthStore.getState().verifyEmail('482913');

    expect(result).toEqual({ ok: true });
    expect(bodies).toEqual([{ verificationId: FIRST_ID, code: '482913' }]);
    expect(useAuthStore.getState().pendingVerification).toBeNull();
  });

  it('keeps the verificationId after a wrong code so the user can try again', async () => {
    server.use(
      apiPost('/auth/verify-email', () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'invalid_verification_code',
            detail: 'The verification code is invalid or has expired. Check the code or request a new one.',
            errors: [{ field: 'code', message: 'The verification code is invalid or has expired.' }],
          },
          { status: 400 },
        ),
      ),
    );

    const result = await useAuthStore.getState().verifyEmail('000000');

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().pendingVerification?.verificationId).toBe(FIRST_ID);
  });

  it('replaces the verificationId with the one resend returns', async () => {
    const resendBodies: unknown[] = [];
    const verifyBodies: unknown[] = [];
    server.use(
      apiPost('/auth/verify-email/resend', async ({ request }) => {
        resendBodies.push(await request.json());
        return challenge(SECOND_ID);
      }),
      apiPost('/auth/verify-email', async ({ request }) => {
        verifyBodies.push(await request.json());
        return HttpResponse.json({ emailVerified: true, verificationTier: 'EMAIL' });
      }),
    );

    expect(await useAuthStore.getState().resendVerification()).toEqual({ ok: true });
    await useAuthStore.getState().verifyEmail('123456');

    expect(resendBodies).toEqual([{ verificationId: FIRST_ID }]);
    expect(verifyBodies).toEqual([{ verificationId: SECOND_ID, code: '123456' }]);
  });

  it('refuses to verify without a pending sign-up instead of posting a missing ID', async () => {
    useAuthStore.setState({ pendingVerification: null });
    const calls: Request[] = [];
    server.use(
      apiPost('/auth/verify-email', ({ request }) => {
        calls.push(request);
        return HttpResponse.json({});
      }),
    );

    const result = await useAuthStore.getState().verifyEmail('482913');

    expect(result.ok).toBe(false);
    expect(calls).toHaveLength(0);
  });
});

describe('login', () => {
  it('opens the session login returned, with the amr read from the token', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(USER_ID, 'EMAIL'))));

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result).toEqual({ ok: true, next: 'signedIn' });
    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().accessToken).toBe(fakeAccessToken(['pwd']));
    expect(useAuthStore.getState().user).toEqual({ id: USER_ID, role: 'USER', verificationTier: 'EMAIL' });
    expect(useAuthStore.getState().authMethods).toEqual(['pwd']);
  });

  it('answers a 401 with the server message and never calls the refresh endpoint', async () => {
    const detail =
      'The email or password is incorrect, or the email address has not been verified yet. ' +
      'If you just signed up, use the code we emailed you first.';
    server.use(apiPost('/auth/login', () => problem(401, 'invalid_credentials', detail)));

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'wrong' });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.failure.message).toBe(detail);
    }
    expect(refreshCalls).toHaveLength(0);
    expect(useAuthStore.getState().status).toBe('signedOut');
  });

  it('rejects a login response with an unknown tier or role', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(USER_ID, 'GOD_MODE'))));
    expect((await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' })).ok).toBe(false);

    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(USER_ID, 'EMAIL', { role: 'ROOT' }))));
    expect((await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' })).ok).toBe(false);

    expect(useAuthStore.getState().status).toBe('signedOut');
  });

  it('rejects a status it does not know instead of treating it as a sign-in', async () => {
    server.use(
      apiPost('/auth/login', () => HttpResponse.json({ ...authenticated(USER_ID, 'EMAIL'), status: 'SOMETHING_NEW' })),
    );

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().status).toBe('signedOut');
  });

  it('keeps only the challenge for a 2FA account: no token, no session', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-20T10:00:00Z') });
    try {
      await startSecondFactor();
    } finally {
      vi.useRealTimers();
    }

    expect(useAuthStore.getState().pendingSecondFactor).toEqual({
      challengeId: CHALLENGE_ID,
      expiresAt: new Date('2026-11-20T10:05:00Z').getTime(),
    });
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('rejects a challenge without an ID rather than storing undefined', async () => {
    server.use(
      apiPost('/auth/login', () =>
        HttpResponse.json({ status: 'SECOND_FACTOR_REQUIRED', session: null, secondFactor: { expiresIn: 300 } }),
      ),
    );

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().pendingSecondFactor).toBeNull();
  });

  describe('Retry-After on a 429', () => {
    const rateLimited = () =>
      HttpResponse.json(
        { status: 429, code: 'rate_limited' },
        { status: 429, headers: { 'Retry-After': '37', 'X-Correlation-Id': 'corr-429' } },
      );

    it('is readable when the backend exposes it', async () => {
      server.use(apiPost('/auth/login', rateLimited));

      const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.failure.retryAfterSeconds).toBe(37);
      }
    });

    it('is hidden by the browser when only the correlation ID is exposed, so the client can only guess', async () => {
      server.use(apiPost('/auth/login', rateLimited, { exposedHeaders: ['X-Correlation-Id'] }));

      const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.failure.retryAfterSeconds).toBe(FALLBACK_RETRY_AFTER_SECONDS);
        expect(result.failure.correlationId).toBe('corr-429');
      }
    });
  });
});

describe('completeSecondFactor', () => {
  it('sends the stored challenge with the code and opens the session with otp in amr', async () => {
    const bodies: unknown[] = [];
    server.use(
      apiPost('/auth/login/second-factor', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(authenticated(USER_ID, 'EMAIL', { amr: ['pwd', 'otp'] }));
      }),
    );
    await startSecondFactor();

    const result = await useAuthStore.getState().completeSecondFactor('492039');

    expect(result).toEqual({ ok: true });
    expect(bodies).toEqual([{ challengeId: CHALLENGE_ID, code: '492039' }]);
    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().authMethods).toEqual(['pwd', 'otp']);
    expect(useAuthStore.getState().pendingSecondFactor).toBeNull();
  });

  it('keeps the challenge after a wrong code and never refreshes on its 401', async () => {
    server.use(apiPost('/auth/login/second-factor', () => problem(401, 'invalid_second_factor', INVALID_SECOND_FACTOR)));
    await startSecondFactor();

    const result = await useAuthStore.getState().completeSecondFactor('000000');

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.failure.code).toBe('invalid_second_factor');
      expect(result.failure.message).toBe(INVALID_SECOND_FACTOR);
    }
    expect(useAuthStore.getState().pendingSecondFactor?.challengeId).toBe(CHALLENGE_ID);
    expect(refreshCalls).toHaveLength(0);
    expect(useAuthStore.getState().status).toBe('signedOut');
  });

  it('refuses to send a code without a challenge in this tab', async () => {
    const calls: Request[] = [];
    server.use(
      apiPost('/auth/login/second-factor', ({ request }) => {
        calls.push(request);
        return HttpResponse.json({});
      }),
    );

    const result = await useAuthStore.getState().completeSecondFactor('123456');

    expect(result.ok).toBe(false);
    expect(calls).toHaveLength(0);
  });
});

describe('logout and revoke-all', () => {
  beforeEach(() => {
    useAuthStore.getState().setSession({ accessToken: 'token-1', user: USER, authMethods: ['pwd'] });
  });

  it('logs out on the server with the bearer token, then clears the tab', async () => {
    const seen: (string | null)[] = [];
    server.use(
      apiPost('/auth/logout', ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await useAuthStore.getState().logout();

    expect(seen).toEqual(['Bearer token-1']);
    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().signOutReason).toBeNull();
  });

  it('still signs this tab out when the logout call fails', async () => {
    server.use(apiPost('/auth/logout', () => problem(503, 'temporarily_unavailable', 'Busy.')));

    await useAuthStore.getState().logout();

    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('signs this tab out after revoking every session, and says why', async () => {
    server.use(apiPost('/auth/sessions/revoke-all', () => new HttpResponse(null, { status: 204 })));

    expect(await useAuthStore.getState().revokeAllDevices()).toEqual({ ok: true });

    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().signOutReason).toBe('revokedAll');
  });

  it('keeps the session when revoke-all fails, so the user can retry', async () => {
    server.use(apiPost('/auth/sessions/revoke-all', () => problem(503, 'temporarily_unavailable', 'Busy.')));

    const result = await useAuthStore.getState().revokeAllDevices();

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().accessToken).toBe('token-1');
  });
});

describe('verification tier', () => {
  it('takes the tier the server reported, keeping the rest of the session', () => {
    useAuthStore.getState().setSession({
      accessToken: 'token-1',
      user: { id: 'user-1', role: 'USER', verificationTier: 'EMAIL' },
      authMethods: ['pwd'],
    });

    useAuthStore.getState().setVerificationTier('PHONE');

    expect(useAuthStore.getState().user).toEqual({ id: 'user-1', role: 'USER', verificationTier: 'PHONE' });
    expect(useAuthStore.getState().accessToken).toBe('token-1');
  });

  it('does nothing without a session', () => {
    useAuthStore.getState().clearSession();

    useAuthStore.getState().setVerificationTier('SELFIE_MATCH');

    expect(useAuthStore.getState().user).toBeNull();
  });
});
