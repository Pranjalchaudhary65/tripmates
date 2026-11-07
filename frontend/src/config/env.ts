/**
 * Parses the API base URL once at startup. A missing or malformed value
 * stops the app with a clear message instead of letting every request fail
 * later against a relative or half-built URL.
 */
export function parseApiBaseUrl(raw: string | undefined): string {
  if (raw === undefined || raw.trim() === '') {
    throw new Error('VITE_API_BASE_URL is not set. Copy .env.example to .env.local.');
  }

  let url: URL;
  try {
    url = new URL(raw.trim());
  } catch {
    throw new Error('VITE_API_BASE_URL must be an absolute URL, e.g. http://localhost:8082/api/v1');
  }

  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new Error('VITE_API_BASE_URL must use http or https.');
  }
  if (url.search !== '' || url.hash !== '') {
    throw new Error('VITE_API_BASE_URL must not contain a query string or fragment.');
  }

  return url.href.replace(/\/+$/, '');
}
