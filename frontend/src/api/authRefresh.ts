import axios, { type AxiosInstance } from 'axios';
import { useAuthStore } from '../stores/authStore';

/**
 * Refresh-token rotation endpoint, relative to the API base URL. The refresh
 * token itself travels only as an httpOnly cookie, which this code never
 * sees; the response body carries the new access token.
 */
export const REFRESH_PATH = '/auth/refresh';

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

export interface AuthInterceptorOptions {
  /** Called once per failed refresh, after the auth store has been cleared. */
  onSessionExpired: () => void;
}

/**
 * Attaches the in-memory access token to every request and handles a 401
 * by refreshing once and retrying the request once.
 *
 * Concurrent 401s share one refresh: the first starts it, the rest await the
 * same promise. Without that, each would send the same refresh cookie, and
 * the server's reuse detection would treat the second use of a rotated token
 * as theft and revoke the whole session.
 *
 * The refresh request goes through a separate Axios instance with no
 * interceptors, so a 401 from the refresh endpoint can never start another
 * refresh.
 */
export function installAuthInterceptors(api: AxiosInstance, options: AuthInterceptorOptions): void {
  const refreshClient = axios.create({
    baseURL: api.defaults.baseURL,
    withCredentials: true,
    timeout: api.defaults.timeout,
  });

  let refreshInFlight: Promise<string> | null = null;

  function refreshAccessToken(): Promise<string> {
    refreshInFlight ??= refreshClient
      .post<unknown>(REFRESH_PATH)
      .then((response) => {
        const accessToken = readAccessToken(response.data);
        useAuthStore.getState().setAccessToken(accessToken);
        return accessToken;
      })
      .catch((error: unknown) => {
        useAuthStore.getState().clearSession();
        options.onSessionExpired();
        throw error;
      })
      .finally(() => {
        refreshInFlight = null;
      });
    return refreshInFlight;
  }

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
      try {
        await refreshAccessToken();
      } catch {
        throw error;
      }
    }

    config.authRetried = true;
    return api.request(config);
  });
}

function bearer(accessToken: string): string {
  return `Bearer ${accessToken}`;
}

function readAccessToken(body: unknown): string {
  if (
    typeof body === 'object' &&
    body !== null &&
    'accessToken' in body &&
    typeof body.accessToken === 'string' &&
    body.accessToken !== ''
  ) {
    return body.accessToken;
  }
  throw new Error('Refresh response did not contain an access token.');
}
