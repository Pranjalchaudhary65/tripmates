import type { VerificationTier } from '../api/accessGrant';

/** Weakest to strongest, as in users.id_verification_tier. */
export const TIER_ORDER: readonly VerificationTier[] = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'];

export function tierAtLeast(tier: VerificationTier, minimum: VerificationTier): boolean {
  return TIER_ORDER.indexOf(tier) >= TIER_ORDER.indexOf(minimum);
}

/** What reaching each tier takes, as a phrase: "This needs ...". */
export const TIER_REQUIREMENT: Record<VerificationTier, string> = {
  NONE: 'a verified email',
  EMAIL: 'a verified email',
  PHONE: 'a verified phone number',
  SELFIE_MATCH: 'the selfie check',
};
