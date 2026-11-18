// @vitest-environment jsdom
import { HttpResponse } from 'msw';
import { apiPost, authenticated, createApiServer } from '../test/apiServer';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { installAuthInterceptors, REFRESH_PATH } from '../api/authRefresh';
import { apiClient } from '../api/client';
import type { RegistrationBody } from '../api/identityApi';
import { FALLBACK_RETRY_AFTER_SECONDS } from '../api/problem';
import { useAuthStore } from './authStore';

const USER = { id: 'u-1', role: 'USER', verificationTier: 'PHONE' } as const;
const CHALLENGE_MESSAGE = 'If this email address can be used, a verification code has been sent to it.';
const FIRST_ID = '5b0c6f1e-6a3e-4c55-9d7a-1f2e3d4c5b6a';
const SECOND_ID = '9e8d7c6b-5a49-4382-a716-1b2c3d4e5f60';
const USER_ID = '0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c';

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
  // The interceptors main.tsx installs, so these tests see what the app sees.
  installAuthInterceptors(apiClient, { onSessionExpired: vi.fn() });
});

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  refreshCalls.length = 0;
  useAuthStore.setState({ accessToken: null, user: null, confirmedCredentials: null, pendingVerification: null });
  server.use(
    apiPost(REFRESH_PATH, ({ request }) => {
      refreshCalls.push(request);
      return HttpResponse.json({ accessToken: 'should-never-be-used' });
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

describe('auth store session state', () => {
  it('starts with no session', () => {
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().confirmedCredentials).toBeNull();
    expect(useAuthStore.getState().pendingVerification).toBeNull();
  });

  it('holds the session and clears it completely', () => {
    useAuthStore.getState().setSession('token-1', USER);
    useAuthStore.setState({ confirmedCredentials: { userId: USER_ID, verificationTier: 'EMAIL' } });
    expect(useAuthStore.getState().accessToken).toBe('token-1');
    expect(useAuthStore.getState().user).toEqual(USER);

    useAuthStore.getState().setAccessToken('token-2');
    expect(useAuthStore.getState().accessToken).toBe('token-2');
    expect(useAuthStore.getState().user).toEqual(USER);

    useAuthStore.getState().clearSession();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().confirmedCredentials).toBeNull();
  });

  it('never writes the token or the verificationId to browser storage', async () => {
    server.use(apiPost('/auth/register', () => challenge(FIRST_ID)));
    useAuthStore.getState().setSession('secret-token', USER);
    await useAuthStore.getState().register(REGISTRATION);

    expect(localStorage).toHaveLength(0);
    expect(sessionStorage).toHaveLength(0);
    expect(document.cookie).not.toContain('secret-token');
    expect(document.cookie).not.toContain(FIRST_ID);
    expect(window.location.href).not.toContain(FIRST_ID);
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
  it('records the confirmed credentials and creates no session', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(USER_ID, 'EMAIL'))));

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result).toEqual({ ok: true });
    expect(useAuthStore.getState().confirmedCredentials).toEqual({ userId: USER_ID, verificationTier: 'EMAIL' });
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('answers a 401 with the server message and never calls the refresh endpoint', async () => {
    const detail =
      'The email or password is incorrect, or the email address has not been verified yet. ' +
      'If you just signed up, use the code we emailed you first.';
    server.use(
      apiPost('/auth/login', () =>
        HttpResponse.json({ status: 401, code: 'invalid_credentials', detail }, { status: 401 }),
      ),
    );
    useAuthStore.setState({ confirmedCredentials: { userId: USER_ID, verificationTier: 'EMAIL' } });

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'wrong' });

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.failure.message).toBe(detail);
    }
    expect(refreshCalls).toHaveLength(0);
    expect(useAuthStore.getState().confirmedCredentials).toBeNull();
  });

  it('rejects a login response with an unknown tier', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(USER_ID, 'GOD_MODE'))));

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().confirmedCredentials).toBeNull();
  });

  it('does not treat a login status it does not know as a sign-in', async () => {
    server.use(
      apiPost('/auth/login', () =>
        HttpResponse.json({ ...authenticated(USER_ID, 'EMAIL'), status: 'SECOND_FACTOR_REQUIRED' }),
      ),
    );

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result.ok).toBe(false);
    expect(useAuthStore.getState().confirmedCredentials).toBeNull();
  });

  it('rejects a response that has no status and session', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json({ userId: USER_ID, verificationTier: 'EMAIL' })));

    const result = await useAuthStore.getState().login({ email: 'asha@example.com', password: 'pw' });

    expect(result.ok).toBe(false);
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
