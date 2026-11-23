import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord, VERIFICATION_TIERS, type VerificationTier } from './accessGrant';
import { API_BASE_URL, apiClient } from './client';

/** Mirrors identity_verifications.status. PENDING is a selfie waiting for a reviewer. */
export type VerificationStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** Mirrors VerificationRejection: a fixed list, never a reviewer's own words. */
export type VerificationRejection =
  | 'LIVENESS_FAILED'
  | 'LOW_SIMILARITY'
  | 'REVIEWER_REJECTED'
  | 'PROFILE_PHOTO_CHANGED'
  | 'REVIEW_EXPIRED';

/** Mirrors LivenessPrompt: what to do in the second frame of a selfie check. */
export type LivenessPrompt = 'TURN_HEAD_LEFT' | 'TURN_HEAD_RIGHT' | 'SMILE' | 'BLINK';

const STATUSES: readonly VerificationStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];
const REJECTIONS: readonly VerificationRejection[] = [
  'LIVENESS_FAILED',
  'LOW_SIMILARITY',
  'REVIEWER_REJECTED',
  'PROFILE_PHOTO_CHANGED',
  'REVIEW_EXPIRED',
];
const PROMPTS: readonly LivenessPrompt[] = ['TURN_HEAD_LEFT', 'TURN_HEAD_RIGHT', 'SMILE', 'BLINK'];

/**
 * A signed, short-lived image link. src is absolute and ready for an img
 * tag; it is built only from the path the server returned, under the
 * configured API origin, so a response can never point an image elsewhere.
 */
export interface MediaLink {
  src: string;
  expiresAt: string;
}

/** VerificationOverviewResponse: the whole verification centre in one call. */
export interface VerificationOverview {
  tier: VerificationTier;
  emailVerified: boolean;
  phone: { verified: boolean; maskedNumber: string | null };
  profilePhoto: MediaLink | null;
  selfie: {
    status: VerificationStatus | null;
    rejectionReason: VerificationRejection | null;
    submittedAt: string | null;
  };
  vouches: { canVouch: boolean; activeGiven: number; limit: number; received: number };
}

/** PhoneCodeSentResponse. */
export interface PhoneCodeSent {
  verificationId: string;
  maskedNumber: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
}

/** SelfieChallengeResponse. The ID is good for one submission. */
export interface SelfieChallenge {
  challengeId: string;
  prompt: LivenessPrompt;
  expiresInSeconds: number;
}

export interface GivenVouch {
  id: string;
  vouchedUserId: string;
  vouchedFirstName: string;
  since: string;
}

/** VouchSummaryResponse. */
export interface VouchSummary {
  canVouch: boolean;
  limit: number;
  given: GivenVouch[];
  receivedCount: number;
}

/** ReviewItemResponse. */
export interface ReviewItem {
  verificationId: string;
  userId: string;
  fullName: string;
  status: VerificationStatus;
  similarityScore: number | null;
  submittedAt: string;
  purgeDueAt: string;
  selfie: MediaLink | null;
  profilePhoto: MediaLink | null;
}

/** PageResponse, the API's one pagination envelope. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type ReviewDecision = 'APPROVE' | 'REJECT';

export function createVerificationApi(client: AxiosInstance, apiBaseUrl: string) {
  const readLink = (raw: unknown) => readMediaLink(raw, apiBaseUrl);

  return {
    async overview(): Promise<VerificationOverview> {
      const response = await client.get<unknown>('/verification');
      return readOverview(response.data, readLink);
    },

    async sendPhoneCode(phoneNumber: string): Promise<PhoneCodeSent> {
      const response = await client.post<unknown>('/verification/phone', { phoneNumber });
      return readPhoneCodeSent(response.data);
    },

    async confirmPhone(verificationId: string, code: string): Promise<VerificationOverview> {
      const response = await client.post<unknown>('/verification/phone/confirm', { verificationId, code });
      return readOverview(response.data, readLink);
    },

    async uploadProfilePhoto(photo: Blob): Promise<VerificationOverview> {
      const form = new FormData();
      form.append('photo', photo, 'photo.jpg');
      const response = await client.put<unknown>('/users/me/profile-photo', form);
      return readOverview(response.data, readLink);
    },

    async startSelfie(): Promise<SelfieChallenge> {
      const response = await client.post<unknown>('/verification/selfie/challenge');
      return readChallenge(response.data);
    },

    async submitSelfie(challengeId: string, neutralFrame: Blob, promptFrame: Blob): Promise<VerificationOverview> {
      const form = new FormData();
      form.append('challengeId', challengeId);
      form.append('neutralFrame', neutralFrame, 'neutral.jpg');
      form.append('promptFrame', promptFrame, 'prompt.jpg');
      const response = await client.post<unknown>('/verification/selfie', form);
      return readOverview(response.data, readLink);
    },

    async vouches(): Promise<VouchSummary> {
      const response = await client.get<unknown>('/vouches');
      return readVouchSummary(response.data);
    },

    async vouch(vouchedUserId: string): Promise<GivenVouch> {
      const response = await client.post<unknown>('/vouches', { vouchedUserId });
      return readGivenVouch(response.data);
    },

    async withdrawVouch(vouchId: string): Promise<void> {
      await client.delete(`/vouches/${encodeURIComponent(vouchId)}`);
    },

    async reviewQueue(page: number): Promise<Page<ReviewItem>> {
      const response = await client.get<unknown>('/admin/verifications', { params: { page, size: 20 } });
      return readPage(response.data, (raw) => readReviewItem(raw, readLink));
    },

    async decide(verificationId: string, decision: ReviewDecision): Promise<ReviewItem> {
      const response = await client.post<unknown>(
        `/admin/verifications/${encodeURIComponent(verificationId)}/decision`,
        { decision },
      );
      return readReviewItem(response.data, readLink);
    },
  };
}

export type VerificationApi = ReturnType<typeof createVerificationApi>;

export const verificationApi: VerificationApi = createVerificationApi(apiClient, API_BASE_URL);

/**
 * Accepts only the server's own media path shape, then joins it to the API
 * base. A path that is absolute, protocol-relative or anything but
 * /media/<token> is refused, so no response can make an img load from
 * another origin.
 */
function readMediaLink(raw: unknown, apiBaseUrl: string): MediaLink | null {
  if (raw === null || raw === undefined) {
    return null;
  }
  if (
    isRecord(raw) &&
    typeof raw['path'] === 'string' &&
    /^\/media\/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(raw['path']) &&
    isNonEmptyString(raw['expiresAt'])
  ) {
    return { src: `${apiBaseUrl}${raw['path']}`, expiresAt: raw['expiresAt'] };
  }
  throw new Error('Unexpected media link.');
}

function readOverview(body: unknown, readLink: (raw: unknown) => MediaLink | null): VerificationOverview {
  if (!isRecord(body)) {
    throw new Error('Unexpected verification state.');
  }
  const tier = VERIFICATION_TIERS.find((candidate) => candidate === body['tier']);
  const phone = body['phone'];
  const selfie = body['selfie'];
  const vouches = body['vouches'];
  if (
    tier !== undefined &&
    typeof body['emailVerified'] === 'boolean' &&
    isRecord(phone) &&
    typeof phone['verified'] === 'boolean' &&
    isRecord(selfie) &&
    isRecord(vouches) &&
    typeof vouches['canVouch'] === 'boolean' &&
    typeof vouches['activeGiven'] === 'number' &&
    typeof vouches['limit'] === 'number' &&
    typeof vouches['received'] === 'number'
  ) {
    return {
      tier,
      emailVerified: body['emailVerified'],
      phone: {
        verified: phone['verified'],
        maskedNumber: typeof phone['maskedNumber'] === 'string' ? phone['maskedNumber'] : null,
      },
      profilePhoto: readLink(body['profilePhoto']),
      selfie: {
        status: oneOf(STATUSES, selfie['status']),
        rejectionReason: oneOf(REJECTIONS, selfie['rejectionReason']),
        submittedAt: typeof selfie['submittedAt'] === 'string' ? selfie['submittedAt'] : null,
      },
      vouches: {
        canVouch: vouches['canVouch'],
        activeGiven: vouches['activeGiven'],
        limit: vouches['limit'],
        received: vouches['received'],
      },
    };
  }
  throw new Error('Unexpected verification state.');
}

function readPhoneCodeSent(body: unknown): PhoneCodeSent {
  if (
    isRecord(body) &&
    isNonEmptyString(body['verificationId']) &&
    typeof body['maskedNumber'] === 'string' &&
    typeof body['expiresIn'] === 'number' &&
    typeof body['resendAfter'] === 'number'
  ) {
    return {
      verificationId: body['verificationId'],
      maskedNumber: body['maskedNumber'],
      expiresInSeconds: body['expiresIn'],
      resendAfterSeconds: body['resendAfter'],
    };
  }
  throw new Error('Unexpected phone code response.');
}

function readChallenge(body: unknown): SelfieChallenge {
  const prompt = isRecord(body) ? oneOf(PROMPTS, body['prompt']) : null;
  if (
    isRecord(body) &&
    isNonEmptyString(body['challengeId']) &&
    prompt !== null &&
    typeof body['expiresIn'] === 'number' &&
    body['expiresIn'] > 0
  ) {
    return { challengeId: body['challengeId'], prompt, expiresInSeconds: body['expiresIn'] };
  }
  throw new Error('Unexpected selfie check.');
}

function readGivenVouch(raw: unknown): GivenVouch {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['vouchedUserId']) &&
    typeof raw['vouchedFirstName'] === 'string' &&
    isNonEmptyString(raw['since'])
  ) {
    return {
      id: raw['id'],
      vouchedUserId: raw['vouchedUserId'],
      vouchedFirstName: raw['vouchedFirstName'],
      since: raw['since'],
    };
  }
  throw new Error('Unexpected vouch.');
}

function readVouchSummary(body: unknown): VouchSummary {
  if (
    isRecord(body) &&
    typeof body['canVouch'] === 'boolean' &&
    typeof body['limit'] === 'number' &&
    Array.isArray(body['given']) &&
    typeof body['receivedCount'] === 'number'
  ) {
    return {
      canVouch: body['canVouch'],
      limit: body['limit'],
      given: body['given'].map(readGivenVouch),
      receivedCount: body['receivedCount'],
    };
  }
  throw new Error('Unexpected vouch summary.');
}

function readReviewItem(raw: unknown, readLink: (raw: unknown) => MediaLink | null): ReviewItem {
  const status = isRecord(raw) ? oneOf(STATUSES, raw['status']) : null;
  if (
    isRecord(raw) &&
    status !== null &&
    isNonEmptyString(raw['verificationId']) &&
    isNonEmptyString(raw['userId']) &&
    typeof raw['fullName'] === 'string' &&
    isNonEmptyString(raw['submittedAt']) &&
    isNonEmptyString(raw['purgeDueAt'])
  ) {
    return {
      verificationId: raw['verificationId'],
      userId: raw['userId'],
      fullName: raw['fullName'],
      status,
      similarityScore: typeof raw['similarityScore'] === 'number' ? raw['similarityScore'] : null,
      submittedAt: raw['submittedAt'],
      purgeDueAt: raw['purgeDueAt'],
      selfie: readLink(raw['selfie']),
      profilePhoto: readLink(raw['profilePhoto']),
    };
  }
  throw new Error('Unexpected review item.');
}

function readPage<T>(body: unknown, readItem: (raw: unknown) => T): Page<T> {
  if (
    isRecord(body) &&
    Array.isArray(body['items']) &&
    typeof body['page'] === 'number' &&
    typeof body['size'] === 'number' &&
    typeof body['totalItems'] === 'number' &&
    typeof body['totalPages'] === 'number'
  ) {
    return {
      items: body['items'].map(readItem),
      page: body['page'],
      size: body['size'],
      totalItems: body['totalItems'],
      totalPages: body['totalPages'],
    };
  }
  throw new Error('Unexpected page.');
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
