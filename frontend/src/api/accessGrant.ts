/** Mirrors users.id_verification_tier in DESIGN.md. */
export type VerificationTier = 'NONE' | 'EMAIL' | 'PHONE' | 'SELFIE_MATCH';

/** Mirrors users.role in DESIGN.md. */
export type UserRole = 'USER' | 'ADMIN';

/** RFC 8176 values the backend puts in the access token's amr claim. */
export type AuthMethod = 'pwd' | 'otp';

export const VERIFICATION_TIERS: readonly VerificationTier[] = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'];
const ROLES: readonly UserRole[] = ['USER', 'ADMIN'];
const AUTH_METHODS: readonly AuthMethod[] = ['pwd', 'otp'];

/** What screens and route guards need to know about the signed-in user. */
export interface SessionUser {
  id: string;
  role: UserRole;
  verificationTier: VerificationTier;
}

/**
 * AccessGrantResponse, the same shape from login, the second-factor step and
 * refresh. authMethods is read from the token for display and route guards
 * only; the server checks the signed claim on every request regardless.
 */
export interface AccessGrant {
  accessToken: string;
  user: SessionUser;
  authMethods: readonly AuthMethod[];
}

/**
 * Checked rather than cast: a body that does not match is a deployment
 * mismatch, and failing here gives one clear error instead of an undefined
 * token being sent as "Bearer undefined" later.
 */
export function readAccessGrant(body: unknown): AccessGrant {
  if (isRecord(body) && isNonEmptyString(body['accessToken'])) {
    const user = readSessionUser(body['user']);
    if (user !== null) {
      return { accessToken: body['accessToken'], user, authMethods: readAuthMethods(body['accessToken']) };
    }
  }
  throw new Error('Unexpected access grant.');
}

function readSessionUser(raw: unknown): SessionUser | null {
  if (!isRecord(raw) || !isNonEmptyString(raw['id'])) {
    return null;
  }
  const role = ROLES.find((candidate) => candidate === raw['role']);
  const tier = VERIFICATION_TIERS.find((candidate) => candidate === raw['verificationTier']);
  if (role === undefined || tier === undefined) {
    return null;
  }
  return { id: raw['id'], role, verificationTier: tier };
}

/**
 * The amr claim, decoded without verifying the signature: this client has no
 * key and needs none, since it only decides what to show. Anything unreadable
 * counts as no methods, which shows less, never more.
 */
export function readAuthMethods(accessToken: string): readonly AuthMethod[] {
  const payload = accessToken.split('.')[1];
  if (payload === undefined) {
    return [];
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const claims: unknown = JSON.parse(atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')));
    const amr = isRecord(claims) ? claims['amr'] : undefined;
    if (!Array.isArray(amr)) {
      return [];
    }
    return AUTH_METHODS.filter((method) => amr.includes(method));
  } catch {
    return [];
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

export function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}
