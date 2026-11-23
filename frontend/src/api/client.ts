import { parseApiBaseUrl } from '../config/env';
import { createApiClient } from './createApiClient';

/** The validated base URL, e.g. https://api.example.com/api/v1. Paths the API returns are relative to it. */
export const API_BASE_URL = parseApiBaseUrl(import.meta.env.VITE_API_BASE_URL);

/** The one Axios instance every feature module calls the backend through. */
export const apiClient = createApiClient(API_BASE_URL);
