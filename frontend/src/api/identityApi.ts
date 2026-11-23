import type { AxiosInstance } from 'axios';
import {
  isNonEmptyString,
  isRecord,
  readAccessGrant,
  VERIFICATION_TIERS,
  type AccessGrant,
  type UserRole,
  type VerificationTier,
} from './accessGrant';
import { apiClient } from './client';

/** Mirrors com.tripmate.identity.entity.Gender. */
export const GENDERS = ['MALE', 'FEMALE', 'NON_BINARY', 'PREFER_NOT_TO_SAY'] as const;
export type Gender = (typeof GENDERS)[number];

/** RegistrationRequest. dateOfBirth is ISO yyyy-MM-dd, as LocalDate expects. */
export interface RegistrationBody {
  email: string;
  password: string;
  fullName: string;
  dateOfBirth: string;
  gender: Gender;
}

/** VerificationChallengeResponse: the same shape whether or not the email already had an account. */
export interface VerificationChallenge {
  verificationId: string;
  message: string;
}

export interface LoginBody {
  email: string;
  password: string;
}

/**
 * LoginResponse, by status. A second-factor challenge is the only thing a
 * correct password buys on a 2FA account: no token and no cookie exist yet.
 */
export type LoginOutcome =
  | { kind: 'signedIn'; grant: AccessGrant }
  | { kind: 'secondFactor'; challengeId: string; expiresInSeconds: number };

/** ActiveSessionResponse. device and ipAddress are produced by the server from fixed vocabularies. */
export interface ActiveSession {
  id: string;
  device: string;
  ipAddress: string;
  signedInAt: string;
  lastActiveAt: string;
  expiresAt: string;
  current: boolean;
}

/** TwoFactorStatusResponse. */
export interface TwoFactorStatus {
  enabled: boolean;
  setupPending: boolean;
  backupCodesRemaining: number;
}

/** TwoFactorSetupResponse: the only response that ever carries the secret. */
export interface TwoFactorSetup {
  secret: string;
  otpauthUri: string;
  issuer: string;
  accountName: string;
}

/** AdminUserResponse. */
export interface AdminUserView {
  id: string;
  email: string;
  fullName: string;
  role: UserRole;
  accountStatus: string;
  verificationTier: VerificationTier;
  twoFactorEnabled: boolean;
  emailVerifiedAt: string | null;
  createdAt: string;
  deletedAt: string | null;
}

/**
 * For the anonymous routes. A 401 from login or the second-factor step
 * means a wrong password or code, not an expired access token, and none of
 * these routes takes a token, so refreshing could never change the answer.
 */
const NO_REFRESH = { skipAuthRefresh: true } as const;

export function createIdentityApi(client: AxiosInstance) {
  return {
    async register(body: RegistrationBody): Promise<VerificationChallenge> {
      const response = await client.post<unknown>('/auth/register', body, NO_REFRESH);
      return readChallenge(response.data);
    },

    async verifyEmail(verificationId: string, code: string): Promise<void> {
      await client.post<unknown>('/auth/verify-email', { verificationId, code }, NO_REFRESH);
    },

    async resendVerification(verificationId: string): Promise<VerificationChallenge> {
      const response = await client.post<unknown>('/auth/verify-email/resend', { verificationId }, NO_REFRESH);
      return readChallenge(response.data);
    },

    async login(body: LoginBody): Promise<LoginOutcome> {
      const response = await client.post<unknown>('/auth/login', body, NO_REFRESH);
      return readLoginOutcome(response.data);
    },

    async completeSecondFactor(challengeId: string, code: string): Promise<AccessGrant> {
      const response = await client.post<unknown>('/auth/login/second-factor', { challengeId, code }, NO_REFRESH);
      return readSignedIn(response.data);
    },

    async logout(): Promise<void> {
      await client.post('/auth/logout');
    },

    async revokeAllDevices(): Promise<void> {
      await client.post('/auth/sessions/revoke-all');
    },

    async listSessions(): Promise<ActiveSession[]> {
      const response = await client.get<unknown>('/auth/sessions');
      const items = isRecord(response.data) ? response.data['items'] : undefined;
      if (!Array.isArray(items)) {
        throw new Error('Unexpected session list.');
      }
      return items.map(readActiveSession);
    },

    async twoFactorStatus(): Promise<TwoFactorStatus> {
      const response = await client.get<unknown>('/auth/2fa');
      return readTwoFactorStatus(response.data);
    },

    async startTwoFactorSetup(password: string): Promise<TwoFactorSetup> {
      const response = await client.post<unknown>('/auth/2fa/setup', { password });
      return readSetup(response.data);
    },

    async confirmTwoFactor(code: string): Promise<string[]> {
      const response = await client.post<unknown>('/auth/2fa/confirm', { code });
      return readBackupCodes(response.data);
    },

    async disableTwoFactor(password: string, code: string): Promise<void> {
      await client.post('/auth/2fa/disable', { password, code });
    },

    async regenerateBackupCodes(password: string, code: string): Promise<string[]> {
      const response = await client.post<unknown>('/auth/2fa/backup-codes', { password, code });
      return readBackupCodes(response.data);
    },

    async adminFindUser(userId: string): Promise<AdminUserView> {
      const response = await client.get<unknown>(`/admin/users/${encodeURIComponent(userId)}`);
      return readAdminUser(response.data);
    },
  };
}

export type IdentityApi = ReturnType<typeof createIdentityApi>;

export const identityApi: IdentityApi = createIdentityApi(apiClient);

function readChallenge(body: unknown): VerificationChallenge {
  if (isRecord(body) && isNonEmptyString(body['verificationId']) && isNonEmptyString(body['message'])) {
    return { verificationId: body['verificationId'], message: body['message'] };
  }
  throw new Error('Unexpected verification challenge response.');
}

/** An unknown status is a step this client cannot complete, never a sign-in. */
function readLoginOutcome(body: unknown): LoginOutcome {
  if (isRecord(body) && body['status'] === 'SECOND_FACTOR_REQUIRED') {
    const challenge = body['secondFactor'];
    if (
      isRecord(challenge) &&
      isNonEmptyString(challenge['challengeId']) &&
      typeof challenge['expiresIn'] === 'number' &&
      challenge['expiresIn'] > 0
    ) {
      return {
        kind: 'secondFactor',
        challengeId: challenge['challengeId'],
        expiresInSeconds: challenge['expiresIn'],
      };
    }
    throw new Error('Unexpected second-factor challenge.');
  }
  return { kind: 'signedIn', grant: readSignedIn(body) };
}

function readSignedIn(body: unknown): AccessGrant {
  if (isRecord(body) && body['status'] === 'AUTHENTICATED') {
    return readAccessGrant(body['session']);
  }
  throw new Error('Unexpected login response.');
}

function readActiveSession(raw: unknown): ActiveSession {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    typeof raw['device'] === 'string' &&
    typeof raw['ipAddress'] === 'string' &&
    isNonEmptyString(raw['signedInAt']) &&
    isNonEmptyString(raw['lastActiveAt']) &&
    isNonEmptyString(raw['expiresAt']) &&
    typeof raw['current'] === 'boolean'
  ) {
    return {
      id: raw['id'],
      device: raw['device'],
      ipAddress: raw['ipAddress'],
      signedInAt: raw['signedInAt'],
      lastActiveAt: raw['lastActiveAt'],
      expiresAt: raw['expiresAt'],
      current: raw['current'],
    };
  }
  throw new Error('Unexpected session.');
}

function readTwoFactorStatus(body: unknown): TwoFactorStatus {
  if (
    isRecord(body) &&
    typeof body['enabled'] === 'boolean' &&
    typeof body['setupPending'] === 'boolean' &&
    typeof body['backupCodesRemaining'] === 'number'
  ) {
    return {
      enabled: body['enabled'],
      setupPending: body['setupPending'],
      backupCodesRemaining: body['backupCodesRemaining'],
    };
  }
  throw new Error('Unexpected two-factor status.');
}

function readSetup(body: unknown): TwoFactorSetup {
  if (
    isRecord(body) &&
    isNonEmptyString(body['secret']) &&
    isNonEmptyString(body['otpauthUri']) &&
    body['otpauthUri'].startsWith('otpauth://totp/') &&
    isNonEmptyString(body['issuer']) &&
    isNonEmptyString(body['accountName'])
  ) {
    return {
      secret: body['secret'],
      otpauthUri: body['otpauthUri'],
      issuer: body['issuer'],
      accountName: body['accountName'],
    };
  }
  throw new Error('Unexpected two-factor setup.');
}

function readBackupCodes(body: unknown): string[] {
  const codes = isRecord(body) ? body['backupCodes'] : undefined;
  if (Array.isArray(codes) && codes.length > 0 && codes.every(isNonEmptyString)) {
    return codes;
  }
  throw new Error('Unexpected backup codes.');
}

function readAdminUser(body: unknown): AdminUserView {
  if (
    isRecord(body) &&
    isNonEmptyString(body['id']) &&
    typeof body['email'] === 'string' &&
    typeof body['fullName'] === 'string' &&
    (body['role'] === 'USER' || body['role'] === 'ADMIN') &&
    typeof body['accountStatus'] === 'string' &&
    typeof body['twoFactorEnabled'] === 'boolean' &&
    isNonEmptyString(body['createdAt'])
  ) {
    const tier = VERIFICATION_TIERS.find((candidate) => candidate === body['verificationTier']);
    if (tier !== undefined) {
      return {
        id: body['id'],
        email: body['email'],
        fullName: body['fullName'],
        role: body['role'],
        accountStatus: body['accountStatus'],
        verificationTier: tier,
        twoFactorEnabled: body['twoFactorEnabled'],
        emailVerifiedAt: typeof body['emailVerifiedAt'] === 'string' ? body['emailVerifiedAt'] : null,
        createdAt: body['createdAt'],
        deletedAt: typeof body['deletedAt'] === 'string' ? body['deletedAt'] : null,
      };
    }
  }
  throw new Error('Unexpected account.');
}
