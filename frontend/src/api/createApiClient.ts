import axios, { type AxiosInstance } from 'axios';

const REQUEST_TIMEOUT_MS = 15_000;

/**
 * withCredentials is on so the browser sends the httpOnly refresh-token
 * cookie cross-origin. The access token is never a cookie; it is attached
 * as a bearer header by the auth interceptor.
 */
export function createApiClient(baseURL: string): AxiosInstance {
  return axios.create({
    baseURL,
    withCredentials: true,
    timeout: REQUEST_TIMEOUT_MS,
  });
}
