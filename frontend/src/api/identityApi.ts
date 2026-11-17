import type { AxiosInstance } from 'axios';
import type { VerificationTier } from '../stores/authStore';
import { apiClient } from './client';

/** Mirrors com.tripmate.identity.entity.Gender. */
export const GENDERS = ['MALE', 'FEMALE', 'NON_BINARY', 'PREFER_NOT_TO_SAY'] as const;
export type Gender = (typeof GENDERS)[number];

const VERIFICATION_TIERS: readonly VerificationTier[] = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'];

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

/** LoginResponse. The credential check passed; no token or session comes with it. */
export interface LoginResult {
  userId: string;
  verificationTier: VerificationTier;
}

export interface LoginBody {
  email: string;
  password: string;
}

/**
 * Every call opts out of the silent-refresh interceptor. A 401 from login
 * means wrong credentials, not an expired access token, and none of these
 * routes takes a token, so refreshing could never change the answer. It
 * would also send a refresh request to an endpoint that does not exist yet.
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

    async login(body: LoginBody): Promise<LoginResult> {
      const response = await client.post<unknown>('/auth/login', body, NO_REFRESH);
      return readLoginResult(response.data);
    },
  };
}

export type IdentityApi = ReturnType<typeof createIdentityApi>;

export const identityApi: IdentityApi = createIdentityApi(apiClient);

/**
 * Responses are checked rather than cast: a body that does not match is a
 * deployment mismatch, and failing here gives one clear error instead of an
 * undefined verificationId being posted back later.
 */
function readChallenge(body: unknown): VerificationChallenge {
  if (isRecord(body) && isNonEmptyString(body['verificationId']) && isNonEmptyString(body['message'])) {
    return { verificationId: body['verificationId'], message: body['message'] };
  }
  throw new Error('Unexpected verification challenge response.');
}

function readLoginResult(body: unknown): LoginResult {
  if (isRecord(body) && isNonEmptyString(body['userId'])) {
    const tier = body['verificationTier'];
    const known = VERIFICATION_TIERS.find((candidate) => candidate === tier);
    if (known !== undefined) {
      return { userId: body['userId'], verificationTier: known };
    }
  }
  throw new Error('Unexpected login response.');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}
