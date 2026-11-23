import { AxiosError, type AxiosInstance } from 'axios';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../stores/authStore';
import { CSRF_HEADER, installAuthInterceptors, REFRESH_PATH, type SessionRefresher } from './authRefresh';
import { createApiClient } from './createApiClient';

const BASE_URL = 'http://api.test/api/v1';
const TRIPS_URL = `${BASE_URL}/trips`;
const REFRESH_URL = `${BASE_URL}${REFRESH_PATH}`;

const OLD_TOKEN = 'old-access-token';
const NEW_TOKEN = 'new-access-token';
const USER = { id: 'u-1', role: 'USER', verificationTier: 'EMAIL' } as const;

const unauthorized = () =>
  HttpResponse.json(
    { status: 401, title: 'Unauthorized', detail: 'Authentication is required to access this resource.' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  );

/** AccessGrantResponse from the refresh endpoint. */
const grant = (accessToken: string) => HttpResponse.json({ accessToken, tokenType: 'Bearer', expiresIn: 900, user: USER });

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

function signedInWith(accessToken: string) {
  useAuthStore.getState().setSession({ accessToken, user: USER, authMethods: ['pwd'] });
}

const server = setupServer();

let api: AxiosInstance;
let refresher: SessionRefresher;
let onSessionExpired: ReturnType<typeof vi.fn<() => void>>;

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});

beforeEach(() => {
  useAuthStore.getState().clearSession();
  api = createApiClient(BASE_URL);
  onSessionExpired = vi.fn<() => void>();
  refresher = installAuthInterceptors(api, { onSessionExpired });
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
    signedInWith(NEW_TOKEN);

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
    server.use(tripsAcceptingNewToken(seen), refreshEndpoint(refreshCalls, () => grant(NEW_TOKEN)));
    signedInWith(OLD_TOKEN);

    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(refreshCalls).toHaveLength(1);
    expect(refreshCalls[0]?.headers.get('Authorization')).toBeNull();
    expect(seen).toEqual([`Bearer ${OLD_TOKEN}`, `Bearer ${NEW_TOKEN}`]);
    expect(useAuthStore.getState().accessToken).toBe(NEW_TOKEN);
    expect(useAuthStore.getState().user).toEqual(USER);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('sends the CSRF header the backend requires on every refresh', async () => {
    const refreshCalls: Request[] = [];
    server.use(tripsAcceptingNewToken([]), refreshEndpoint(refreshCalls, () => grant(NEW_TOKEN)));
    signedInWith(OLD_TOKEN);

    await api.get('/trips');
    await refresher.refresh();

    expect(refreshCalls).toHaveLength(2);
    for (const call of refreshCalls) {
      expect(call.headers.get(CSRF_HEADER)).toBe('1');
    }
  });

  it('does not send the CSRF header on other requests', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get(TRIPS_URL, ({ request }) => {
        seen.push(request.headers.get(CSRF_HEADER));
        return HttpResponse.json({ items: [] });
      }),
    );

    await api.get('/trips');

    expect(seen).toEqual([null]);
  });

  it('refreshes on a 401 even when the app has no token yet', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(tripsAcceptingNewToken(seen), refreshEndpoint(refreshCalls, () => grant(NEW_TOKEN)));

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
      refreshEndpoint(refreshCalls, () => grant(NEW_TOKEN)),
    );
    signedInWith(OLD_TOKEN);

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
        return grant(NEW_TOKEN);
      }),
    );
    signedInWith(OLD_TOKEN);

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

  it('shares one call between the start-up refresh and a 401 that arrives while it is in flight', async () => {
    const refreshCalls: Request[] = [];
    let releaseRefresh: () => void = () => undefined;
    const refreshGate = new Promise<void>((resolve) => {
      releaseRefresh = resolve;
    });
    server.use(
      tripsAcceptingNewToken([]),
      refreshEndpoint(refreshCalls, async () => {
        await refreshGate;
        return grant(NEW_TOKEN);
      }),
    );

    const startup = refresher.refresh();
    const request = api.get('/trips');
    await vi.waitFor(() => {
      expect(refreshCalls).toHaveLength(1);
    });
    releaseRefresh();

    expect(await startup).toEqual({ kind: 'refreshed', accessToken: NEW_TOKEN });
    expect((await request).status).toBe(200);
    expect(refreshCalls).toHaveLength(1);
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
        signedInWith(NEW_TOKEN);
        return unauthorized();
      }),
      refreshEndpoint(refreshCalls, () => grant('unused')),
    );
    signedInWith(OLD_TOKEN);

    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(refreshCalls).toHaveLength(0);
    expect(seen).toEqual([`Bearer ${OLD_TOKEN}`, `Bearer ${NEW_TOKEN}`]);
  });

  it('does not refresh on a 403, since a new token would not change the answer', async () => {
    const refreshCalls: Request[] = [];
    server.use(
      http.get(TRIPS_URL, () => HttpResponse.json({ status: 403 }, { status: 403 })),
      refreshEndpoint(refreshCalls, () => grant(NEW_TOKEN)),
    );
    signedInWith(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(403);
    expect(refreshCalls).toHaveLength(0);
  });

  it.each(['/auth/login', '/auth/login/second-factor'])(
    'does not refresh on a 401 from %s, which opts out with skipAuthRefresh',
    async (path) => {
      const refreshCalls: Request[] = [];
      server.use(
        http.post(`${BASE_URL}${path}`, unauthorized),
        refreshEndpoint(refreshCalls, () => grant(NEW_TOKEN)),
      );

      const error = await rejectionOf(api.post(path, {}, { skipAuthRefresh: true }));

      expect(error.response?.status).toBe(401);
      expect(refreshCalls).toHaveLength(0);
      expect(onSessionExpired).not.toHaveBeenCalled();
    },
  );
});

describe('failed refresh', () => {
  it('never refreshes again on a 401 from the refresh call, then clears the session and reports it once', async () => {
    const seen: (string | null)[] = [];
    const refreshCalls: Request[] = [];
    server.use(tripsAcceptingNewToken(seen), refreshEndpoint(refreshCalls, unauthorized));
    signedInWith(OLD_TOKEN);

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
    expect(useAuthStore.getState().status).toBe('signedOut');
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
    signedInWith(OLD_TOKEN);

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
    signedInWith(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(401);
    expect(refreshCalls).toHaveLength(1);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('reports nothing when the start-up refresh finds no session, since none ended', async () => {
    server.use(http.post(REFRESH_URL, unauthorized));
    useAuthStore.setState({ status: 'starting' });

    expect(await refresher.refresh()).toEqual({ kind: 'expired' });

    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(useAuthStore.getState().signOutReason).toBeNull();
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('ends the session on a 403 from refresh, which no retry can fix', async () => {
    server.use(
      http.get(TRIPS_URL, unauthorized),
      http.post(REFRESH_URL, () => HttpResponse.json({ status: 403, code: 'csrf_rejected' }, { status: 403 })),
    );
    signedInWith(OLD_TOKEN);

    await rejectionOf(api.get('/trips'));

    expect(useAuthStore.getState().status).toBe('signedOut');
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });
});

describe('refresh unavailable', () => {
  it('keeps the session on a 503 from refresh and hands the 503 to the caller', async () => {
    server.use(
      http.get(TRIPS_URL, unauthorized),
      http.post(REFRESH_URL, () =>
        HttpResponse.json(
          { status: 503, code: 'temporarily_unavailable' },
          { status: 503, headers: { 'Retry-After': '30' } },
        ),
      ),
    );
    signedInWith(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response?.status).toBe(503);
    expect(error.response?.headers['retry-after']).toBe('30');
    expect(useAuthStore.getState().status).toBe('signedIn');
    expect(useAuthStore.getState().accessToken).toBe(OLD_TOKEN);
    expect(useAuthStore.getState().user).toEqual(USER);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('keeps the session when the refresh request cannot reach the server', async () => {
    server.use(
      http.get(TRIPS_URL, unauthorized),
      http.post(REFRESH_URL, () => HttpResponse.error()),
    );
    signedInWith(OLD_TOKEN);

    const error = await rejectionOf(api.get('/trips'));

    expect(error.response).toBeUndefined();
    expect(useAuthStore.getState().accessToken).toBe(OLD_TOKEN);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it('tries the refresh again on the next 401 once the server is back', async () => {
    const refreshCalls: Request[] = [];
    let available = false;
    server.use(
      tripsAcceptingNewToken([]),
      refreshEndpoint(refreshCalls, () =>
        available ? grant(NEW_TOKEN) : HttpResponse.json({ status: 503 }, { status: 503 }),
      ),
    );
    signedInWith(OLD_TOKEN);

    expect((await rejectionOf(api.get('/trips'))).response?.status).toBe(503);
    available = true;
    const response = await api.get('/trips');

    expect(response.status).toBe(200);
    expect(refreshCalls).toHaveLength(2);
    expect(useAuthStore.getState().accessToken).toBe(NEW_TOKEN);
  });
});
