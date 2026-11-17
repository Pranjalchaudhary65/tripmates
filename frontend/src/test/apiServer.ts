import { http, HttpResponse, type HttpResponseResolver } from 'msw';
import { setupServer } from 'msw/node';

/** Matches test.env.VITE_API_BASE_URL in vite.config.ts. */
export const API_BASE_URL = 'http://api.test/api/v1';

/** Matches test.environmentOptions.jsdom.url in vite.config.ts, and the backend's allowed dev origin. */
export const APP_ORIGIN = 'http://localhost:5173';

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

/** An MSW server that also answers CORS preflights the way the backend does. */
export function createApiServer() {
  return setupServer(
    http.options(`${API_BASE_URL}/*`, () =>
      withCors(
        new HttpResponse(null, {
          status: 200,
          headers: {
            'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE',
            'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Correlation-Id',
          },
        }),
      ),
    ),
  );
}
