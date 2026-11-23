import { http, HttpResponse, type HttpResponseResolver } from 'msw';
import { setupServer } from 'msw/node';

/** Matches test.env.VITE_API_BASE_URL in vite.config.ts. */
export const API_BASE_URL = 'http://api.test/api/v1';

/** Matches test.environmentOptions.jsdom.url in vite.config.ts, and the backend's allowed dev origin. */
export const APP_ORIGIN = 'http://localhost:5173';

export interface GrantOptions {
  role?: string;
  amr?: readonly string[];
  /** Distinguishes tokens in assertions. */
  tokenId?: string;
}

/**
 * A JWT-shaped access token whose payload carries the given amr. The
 * signature is not real: the client never verifies one, it only reads amr.
 */
export function fakeAccessToken(amr: readonly string[] = ['pwd'], tokenId = 'token-1'): string {
  const encode = (value: unknown) =>
    btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${encode({ alg: 'HS256', typ: 'at+jwt' })}.${encode({ sub: 'user', jti: tokenId, amr })}.c2lnbmF0dXJl`;
}

/** AccessGrantResponse, as login, the second-factor step and refresh return it. */
export function accessGrant(userId: string, verificationTier: string, options: GrantOptions = {}) {
  return {
    accessToken: fakeAccessToken(options.amr, options.tokenId),
    tokenType: 'Bearer',
    expiresIn: 900,
    user: { id: userId, role: options.role ?? 'USER', verificationTier },
  };
}

/** A successful LoginResponse as the backend sends it. */
export function authenticated(userId: string, verificationTier: string, options: GrantOptions = {}) {
  return { status: 'AUTHENTICATED', session: accessGrant(userId, verificationTier, options), secondFactor: null };
}

/** LoginResponse for an account with 2FA: a challenge and nothing else. */
export function secondFactorRequired(challengeId: string, expiresIn = 300) {
  return {
    status: 'SECOND_FACTOR_REQUIRED',
    session: null,
    secondFactor: { challengeId, expiresIn, methods: ['TOTP', 'BACKUP_CODE'] },
  };
}

/** An RFC 9457 body the way GlobalExceptionHandler writes one for a ProblemType. */
export function problem(status: number, code: string, detail: string, init: { headers?: Record<string, string> } = {}) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail, code },
    { status, headers: { 'Content-Type': 'application/problem+json', ...init.headers } },
  );
}

/** ApiCorsFilter's exposed headers. */
export const EXPOSED_HEADERS = ['X-Correlation-Id', 'Retry-After'];

export interface CorsOptions {
  /** Headers page scripts may read; defaults to what the backend exposes. */
  exposedHeaders?: readonly string[];
}

/**
 * jsdom applies CORS to mocked responses just as a browser applies it to
 * real ones, so every mocked API response carries the headers ApiCorsFilter
 * sends. A test that leaves one out sees exactly what the browser would.
 */
export function withCors(response: Response, options: CorsOptions = {}): Response {
  const exposed = options.exposedHeaders ?? EXPOSED_HEADERS;
  response.headers.set('Access-Control-Allow-Origin', APP_ORIGIN);
  response.headers.set('Access-Control-Allow-Credentials', 'true');
  if (exposed.length > 0) {
    response.headers.set('Access-Control-Expose-Headers', exposed.join(', '));
  }
  return response;
}

/** A POST handler under the API base URL whose responses carry the backend's CORS headers. */
export function apiPost(path: string, resolver: HttpResponseResolver, options: CorsOptions = {}) {
  return http.post(`${API_BASE_URL}${path}`, async (info) => {
    const response = await resolver(info);
    if (!(response instanceof Response)) {
      throw new Error(`Handler for ${path} must return a Response.`);
    }
    return withCors(response, options);
  });
}

/** A GET handler under the API base URL, with the backend's CORS headers. */
export function apiGet(path: string, resolver: HttpResponseResolver, options: CorsOptions = {}) {
  return http.get(`${API_BASE_URL}${path}`, async (info) => {
    const response = await resolver(info);
    if (!(response instanceof Response)) {
      throw new Error(`Handler for ${path} must return a Response.`);
    }
    return withCors(response, options);
  });
}

/** A PUT handler under the API base URL, with the backend's CORS headers. */
export function apiPut(path: string, resolver: HttpResponseResolver, options: CorsOptions = {}) {
  return http.put(`${API_BASE_URL}${path}`, async (info) => {
    const response = await resolver(info);
    if (!(response instanceof Response)) {
      throw new Error(`Handler for ${path} must return a Response.`);
    }
    return withCors(response, options);
  });
}

/** A DELETE handler under the API base URL, with the backend's CORS headers. */
export function apiDelete(path: string, resolver: HttpResponseResolver, options: CorsOptions = {}) {
  return http.delete(`${API_BASE_URL}${path}`, async (info) => {
    const response = await resolver(info);
    if (!(response instanceof Response)) {
      throw new Error(`Handler for ${path} must return a Response.`);
    }
    return withCors(response, options);
  });
}

/** An MSW server that also answers CORS preflights the way the backend does. */
export function createApiServer() {
  return setupServer(
    http.options(`${API_BASE_URL}/*`, () =>
      withCors(
        new HttpResponse(null, {
          status: 200,
          headers: {
            'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE',
            'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Correlation-Id, X-TripMates-CSRF',
          },
        }),
      ),
    ),
  );
}
