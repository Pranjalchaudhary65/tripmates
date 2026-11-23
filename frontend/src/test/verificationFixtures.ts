import type { VerificationTier } from '../stores/authStore';

/** A signed media path in the exact shape the backend issues. */
export const PHOTO_PATH = '/media/cHJvZmlsZS1waG90b3MvYS5qcGd8MTc5OTk5OTk5OQ.c2lnbmF0dXJl';
export const SELFIE_PATH = '/media/c2VsZmllcy9iLmpwZ3wxNzk5OTk5OTk5.c2lnbmF0dXJl';

export interface OverviewOptions {
  tier?: VerificationTier;
  phoneVerified?: boolean;
  maskedNumber?: string | null;
  photo?: boolean;
  selfieStatus?: 'PENDING' | 'APPROVED' | 'REJECTED' | null;
  rejectionReason?: string | null;
  canVouch?: boolean;
  received?: number;
}

/** VerificationOverviewResponse as the backend writes it. */
export function overviewBody(options: OverviewOptions = {}) {
  const tier = options.tier ?? 'EMAIL';
  return {
    tier,
    emailVerified: true,
    phone: {
      verified: options.phoneVerified ?? (tier === 'PHONE' || tier === 'SELFIE_MATCH'),
      maskedNumber: options.maskedNumber ?? null,
    },
    profilePhoto: options.photo === true ? { path: PHOTO_PATH, expiresAt: '2026-11-23T10:05:00Z' } : null,
    selfie: {
      status: options.selfieStatus ?? null,
      rejectionReason: options.rejectionReason ?? null,
      submittedAt: options.selfieStatus == null ? null : '2026-11-23T10:00:00Z',
    },
    vouches: { canVouch: options.canVouch ?? tier === 'SELFIE_MATCH', activeGiven: 0, limit: 5, received: options.received ?? 0 },
  };
}
