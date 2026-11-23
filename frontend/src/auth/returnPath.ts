/**
 * Where a guarded route sends the user back to after login, carried in
 * router state. Only a path inside this app is accepted: "//evil.example"
 * and "/\evil.example" are protocol-relative URLs to another site, and a
 * return path is exactly where an open redirect would hide.
 */
export interface ReturnState {
  from: string;
}

const AUTH_PAGES = ['/login', '/signup', '/verify-email'];

export function readReturnPath(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('from' in state) || typeof state.from !== 'string') {
    return null;
  }
  const from = state.from;
  if (!from.startsWith('/') || from.startsWith('//') || from.startsWith('/\\')) {
    return null;
  }
  const pathname = from.split(/[?#]/)[0] ?? from;
  if (AUTH_PAGES.some((page) => pathname === page || pathname.startsWith(`${page}/`))) {
    return null;
  }
  return from;
}
