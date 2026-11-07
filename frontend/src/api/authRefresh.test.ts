import { AxiosError, type AxiosInstance } from 'axios';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../stores/authStore';
import { installAuthInterceptors, REFRESH_PATH } from './authRefresh';
import { createApiClient } from './createApiClient';

const BASE_URL = 'http://api.test/api/v1';
const TRIPS_URL = `${BASE_URL}/trips`;
const REFRESH_URL = `${BASE_URL}${REFRESH_PATH}`;

const OLD_TOKEN = 'old-access-token';
const NEW_TOKEN = 'new-access-token';

const unauthorized = () =>
  HttpResponse.json(
    { status: 401, title: 'Unauthorized', detail: 'Authentication is required to access this resource.' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  );

/** Trips endpoint that accepts only NEW_TOKEN and records every Authorization it saw. */
function tripsAcceptingNewToken(seen: (string | null)[]) {
  return http.get(TRIPS_URL, ({ request }) => {
    const authorization = request.headers.get('Authorization');
    seen.push(authorization);
    return authorization === `Bearer ${NEW_TOKEN}` ? HttpResponse.json({ items: [] }) : unauthorized();
  });
}

/** Refresh endpoint that records each call and answers with the given resolver. */
function refreshEndpoint(calls: Request[], respond: () => Response | Promise<Response>) {
  return http.post(REFRESH_URL, ({ request }) => {
    calls.push(request);
    return respond();
  });
}

const server = setupServer();

let api: AxiosInstance;
let onSessionExpired: ReturnType<typeof vi.fn<() => void>>;

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});

beforeEach(() => {
  useAuthStore.getState().clearSession();
  api = createApiClient(BASE_URL);
  onSessionExpired = vi.fn<() => void>();
  installAuthInterceptors(api, { onSessionExpired });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});

async function rejectionOf(promise: Promise<unknown>): Promise<AxiosError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof AxiosError) {
      return error;
    }
    throw error;
  }
  throw new Error('Expected the request to fail, but it succeeded.');
}

describe('API client', () => {
  it('sends credentials and uses the configured base URL', () => {
    expect(api.defaults.withCredentials).toBe(true);
    expect(api.defaults.baseURL).toBe(BASE_URL);
  });

  it('attaches the in-memory access token as a bearer header', async () => {
    const seen: (string | null)[] = [];
    server.use(tripsAcceptingNewToken(seen));
    useAuthStore.getState().setAccessToken(NEW_TOKEN);

    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(seen).toEqual([`Bearer ${NEW_TOKEN}`]);
  });

  it('sends no Authorization header when there is no token', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(TRIPS_URL, ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return HttpResponse.json({ items: [] });
      }),
    );

    await api.get('/trips');

    expect(seen).toEqual([null]);
  });
});

describe('silent refresh', () => {
  it('refreshes once on a 401 and retries the original request once with the new token', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(
      tripsAcceptingNewToken(seen),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ accessToken: NEW_TOKEN })),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(refreshCalls).toHaveLength(1);
    expect(refreshCalls[0]?.headers.get('Authorization')).toBeNull();
    expect(seen).toEqual([`Bearer ${OLD_TOKEN}`, `Bearer ${NEW_TOKEN}`]);
    expect(useAuthStore.getState().accessToken).toBe(NEW_TOKEN);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('refreshes on a 401 even when the app has no token yet, as after a page reload', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(
      tripsAcceptingNewToken(seen),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ accessToken: NEW_TOKEN })),
    );

    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(refreshCalls).toHaveLength(1);
    expect(seen).toEqual([null, `Bearer ${NEW_TOKEN}`]);
  });

  it('returns the 401 if the retried request fails again, without a second refresh', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(
      http.get(TRIPS_URL, ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return unauthorized();
      }),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ accessToken: NEW_TOKEN })),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(401);
    expect(refreshCalls).toHaveLength(1);
    expect(seen).toHaveLength(2);
  });

  it('makes exactly one refresh call when several requests fail with 401 at once', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    let releaseRefresh: () => void = () => undefined;
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });
    server.use(
      tripsAcceptingNewToken(seen),
      refreshEndpoint(refreshCalls, async () => {
        await refreshGate;
        return HttpResponse.json({ accessToken: NEW_TOKEN });
      }),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const requests = Promise.all([api.get('/trips'), api.get('/trips'), api.get('/trips')]);
    await vi.waitFor(() => {
      expect(seen.filter((value) => value === `Bearer ${OLD_TOKEN}`)).toHaveLength(3);
      expect(refreshCalls).toHaveLength(1);
    });
    releaseRefresh();
    const responses = await requests;

    expect(responses.map((response) => response.status)).toEqual([200, 200, 200]);
    expect(refreshCalls).toHaveLength(1);
    expect(seen.filter((value) => value === `Bearer ${NEW_TOKEN}`)).toHaveLength(3);
  });

  it('retries without refreshing when another refresh already replaced the token', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(
      http.get(TRIPS_URL, ({ request }) => {
        const authorization = request.headers.get('Authorization');
        seen.push(authorization);
        if (authorization === `Bearer ${NEW_TOKEN}`) {
          return HttpResponse.json({ items: [] });
        }
        useAuthStore.getState().setAccessToken(NEW_TOKEN);
        return unauthorized();
      }),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ accessToken: 'unused' })),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(refreshCalls).toHaveLength(0);
    expect(seen).toEqual([`Bearer ${OLD_TOKEN}`, `Bearer ${NEW_TOKEN}`]);
  });

  it('does not refresh on a 403, since a new token would not change the answer', async () => {
    const refreshCalls: Request[] = [];
    server.use(
      http.get(TRIPS_URL, () => HttpResponse.json({ status: 403 }, { status: 403 })),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ accessToken: NEW_TOKEN })),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(403);
    expect(refreshCalls).toHaveLength(0);
  });

  it('does not refresh when a request opts out with skipAuthRefresh', async () => {
    const refreshCalls: Request[] = [];
    server.use(
      http.post(`${BASE_URL}/auth/login`, unauthorized),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ accessToken: NEW_TOKEN })),
    );

    const error = await rejectionOf(api.post('/auth/login', {}, { skipAuthRefresh: true }));

    expect(error.response?.status).toBe(401);
    expect(refreshCalls).toHaveLength(0);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});

describe('failed refresh', () => {
  it('never refreshes again on a 401 from the refresh call, then clears the session and redirects once', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(tripsAcceptingNewToken(seen), refreshEndpoint(refreshCalls, unauthorized));
    useAuthStore.getState().setSession(OLD_TOKEN, { id: 'u-1', role: 'USER', verificationTier: 'EMAIL' });

    const errors = await Promise.all([
      rejectionOf(api.get('/trips')),
      rejectionOf(api.get('/trips')),
      rejectionOf(api.get('/trips')),
    ]);

    expect(refreshCalls).toHaveLength(1);
    expect(errors.map((error) => error.config?.url)).toEqual(['/trips', '/trips', '/trips']);
    expect(errors.map((error) => error.response?.status)).toEqual([401, 401, 401]);
    expect(seen).toHaveLength(3);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('does not start a second refresh for a 401 that arrives after the session was cleared', async () => {
    const refreshCalls: Request[] = [];
    let releaseSlowRequest: () => void = () => undefined;
    const slowGate = new Promise<void>((resolve) => {
      releaseSlowRequest = resolve;
    });
    server.use(
      http.get(TRIPS_URL, unauthorized),
      http.get(`${BASE_URL}/slow`, async () => {
        await slowGate;
        return unauthorized();
      }),
      refreshEndpoint(refreshCalls, unauthorized),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const slow = rejectionOf(api.get('/slow'));
    await rejectionOf(api.get('/trips'));
    releaseSlowRequest();
    const lateError = await slow;

    expect(lateError.response?.status).toBe(401);
    expect(refreshCalls).toHaveLength(1);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('treats a refresh response without an access token as a failure', async () => {
    const refreshCalls: Request[] = [];
    server.use(
      http.get(TRIPS_URL, unauthorized),
      refreshEndpoint(refreshCalls, () => HttpResponse.json({ token: NEW_TOKEN })),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(401);
    expect(refreshCalls).toHaveLength(1);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('treats a server error from the refresh endpoint as a failure', async () => {
    server.use(
      http.get(TRIPS_URL, unauthorized),
      http.post(REFRESH_URL, () => HttpResponse.json({ status: 503 }, { status: 503 })),
    );
    useAuthStore.getState().setAccessToken(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(401);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });
});
