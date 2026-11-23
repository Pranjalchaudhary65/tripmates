import axios, { type AxiosInstance } from 'axios';
import { useAuthStore } from '../stores/authStore';
import { readAccessGrant } from './accessGrant';

/**
 * Refresh-token rotation endpoint, relative to the API base URL. The refresh
 * token itself travels only as an httpOnly cookie, which this code never
 * sees; the response body carries the new access token.
 */
export const REFRESH_PATH = '/auth/refresh';

/**
 * The backend refuses a refresh without this header (CsrfHeaderFilter). A
 * cross-site page cannot set a custom header on a credentialed request
 * without passing CORS, so its presence proves the request came from this app.
 */
export const CSRF_HEADER = 'X-TripMates-CSRF';
export const CSRF_HEADER_VALUE = '1';

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * For requests whose 401 means something other than an expired access
     * token, such as a wrong password on login. The 401 goes straight back
     * to the caller instead of starting a refresh.
     */
    skipAuthRefresh?: boolean;
    /** Marks the single retry made after a refresh, so it is never refreshed again. */
    authRetried?: boolean;
  }
}

/**
 * How a refresh ended.
 * - refreshed: the store holds the new session.
 * - expired: the server refused the cookie; the store has been cleared.
 * - unavailable: no answer about the session (503, 429, a 5xx, the network);
 *   the store is untouched, because the session may well still be valid and
 *   signing the user out over an outage would be wrong.
 */
export type RefreshOutcome =
  | { kind: 'refreshed'; accessToken: string }
  | { kind: 'expired' }
  | { kind: 'unavailable'; error: unknown };

export interface SessionRefresher {
  /** Joins the refresh already in flight, if there is one. */
  refresh: () => Promise<RefreshOutcome>;
}

export interface AuthInterceptorOptions {
  /**
   * Called once per refresh that ended a session this tab held, after the
   * auth store has been cleared. Not called when there was no session to
   * end, such as the start-up refresh of a visitor who is not signed in.
   */
  onSessionExpired: () => void;
}

/**
 * Attaches the in-memory access token to every request and handles a 401
 * by refreshing once and retrying the request once. Returns the refresher,
 * so the start-up session restore shares its single flight.
 *
 * Concurrent refreshes share one request: the first starts it, the rest
 * await the same promise. Without that, each would send the same refresh
 * cookie, and the server's reuse detection would treat the second use of a
 * rotated token as theft and revoke the whole session.
 *
 * The refresh request goes through a separate Axios instance with no
 * interceptors, so a 401 from the refresh endpoint can never start another
 * refresh, and a retried request is marked so it is never refreshed twice:
 * one 401 costs at most one refresh and one retry.
 */
export function installAuthInterceptors(api: AxiosInstance, options: AuthInterceptorOptions): SessionRefresher {
  const refreshClient = axios.create({
    baseURL: api.defaults.baseURL,
    withCredentials: true,
    timeout: api.defaults.timeout,
    headers: { [CSRF_HEADER]: CSRF_HEADER_VALUE },
  });

  let refreshInFlight: Promise<RefreshOutcome> | null = null;

  const refresher: SessionRefresher = {
    refresh() {
      if (refreshInFlight !== null) {
        return refreshInFlight;
      }
      const hadSession = useAuthStore.getState().status === 'signedIn';
      const sessionGone = (): RefreshOutcome => {
        useAuthStore.getState().clearSession();
        if (hadSession) {
          options.onSessionExpired();
        }
        return { kind: 'expired' };
      };
      refreshInFlight = refreshClient
        .post<unknown>(REFRESH_PATH)
        .then(
          (response): RefreshOutcome => {
            try {
              const grant = readAccessGrant(response.data);
              useAuthStore.getState().setSession(grant);
              return { kind: 'refreshed', accessToken: grant.accessToken };
            } catch {
              return sessionGone();
            }
          },
          (error: unknown): RefreshOutcome => {
            if (sessionIsGone(error)) {
              return sessionGone();
            }
            return { kind: 'unavailable', error };
          },
        )
        .finally(() => {
          refreshInFlight = null;
        });
      return refreshInFlight;
    },
  };

  api.interceptors.request.use((config) => {
    const accessToken = useAuthStore.getState().accessToken;
    if (accessToken === null) {
      config.headers.delete('Authorization');
    } else {
      config.headers.set('Authorization', bearer(accessToken));
    }
    return config;
  });

  api.interceptors.response.use(undefined, async (error: unknown) => {
    if (!axios.isAxiosError(error) || error.response?.status !== 401 || error.config === undefined) {
      throw error;
    }
    const config = error.config;
    if (config.skipAuthRefresh === true || config.authRetried === true) {
      throw error;
    }

    const sentAuthorization = config.headers.get('Authorization');
    const currentToken = useAuthStore.getState().accessToken;

    // The request carried a token but the store is now empty: the session
    // ended (a failed refresh or a logout) while this request was in flight.
    if (currentToken === null && typeof sentAuthorization === 'string') {
      throw error;
    }

    // If the token was already replaced by a refresh that finished while
    // this request was in flight, retry with the new one rather than
    // spending another refresh.
    if (currentToken === null || sentAuthorization === bearer(currentToken)) {
      const outcome = await refresher.refresh();
      if (outcome.kind === 'expired') {
        throw error;
      }
      if (outcome.kind === 'unavailable') {
        // The caller sees why it failed (a 503 with its Retry-After, or the
        // network), not a 401 that would read as "signed out".
        throw outcome.error;
      }
    }

    config.authRetried = true;
    return api.request(config);
  });

  return refresher;
}

/**
 * Only an answer from the server about the cookie itself ends the session:
 * 401 (missing, expired, revoked or reused) and 403 (the request was not
 * accepted as coming from this app, which a retry cannot fix).
 */
function sessionIsGone(error: unknown): boolean {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined;
  return status === 401 || status === 403;
}

function bearer(accessToken: string): string {
  return `Bearer ${accessToken}`;
}
