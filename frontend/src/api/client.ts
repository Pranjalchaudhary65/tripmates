import { parseApiBaseUrl } from '../config/env';
import { createApiClient } from './createApiClient';

/** The one Axios instance every feature module calls the backend through. */
export const apiClient = createApiClient(parseApiBaseUrl(import.meta.env.VITE_API_BASE_URL));
