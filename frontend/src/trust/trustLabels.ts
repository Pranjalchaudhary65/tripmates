import type { Deduction, ReportReason, TrustBreakdown } from '../api/trustApi';

/** Words for trust and review codes; the server only ever sends the codes. */

export const TIER_NAMES: Record<TrustBreakdown['verification']['tier'], string> = {
  NONE: 'Not verified',
  EMAIL: 'Email verified',
  PHONE: 'Phone verified',
  SELFIE_MATCH: 'Selfie matched',
};

const FLAG_NAMES: Record<string, string> = {
  MONEY_REQUEST: 'asking for money',
  OFF_PLATFORM_PUSH: 'moving off TripMates',
  HARASSMENT: 'possible harassment',
};

/** One line for a reliability deduction, about the user's own behaviour. */
export function deductionLabel(deduction: Deduction): string {
  switch (deduction.type) {
    case 'MEETUP_NO_SHOW':
      return 'Did not turn up to a confirmed meet-up';
    case 'MEETUP_LATE_CANCEL':
      return 'Cancelled a meet-up less than a day before, for a change of plan';
    case 'SETTLEMENT_OVERDUE':
      return deduction.detail === 'PAYER'
        ? 'Left a suggested payment unsent for over a week'
        : 'Left a payment sent to you unconfirmed for over a week';
    case 'CHAT_MESSAGE_FLAGGED':
      return `A message you sent was flagged (${FLAG_NAMES[deduction.detail ?? ''] ?? 'other'})`;
    case 'VOUCH_PENALIZED':
      return 'Someone you vouched for was suspended';
  }
}

export const REPORT_REASON_LABELS: Record<ReportReason, string> = {
  HARASSMENT: 'Harassment or abuse',
  SCAM_OR_MONEY_REQUEST: 'Scam or asking for money',
  FAKE_PROFILE: 'Fake profile or not who they say',
  UNSAFE_BEHAVIOUR: 'Made me feel unsafe',
  OTHER: 'Something else',
};

const DAY = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Asia/Kolkata' });

export function formatDay(instantOrDate: string): string {
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(instantOrDate) ? new Date(`${instantOrDate}T00:00:00+05:30`) : new Date(instantOrDate);
  return DAY.format(parsed);
}

export function stars(rating: number): string {
  return `${'★'.repeat(rating)}${'☆'.repeat(5 - rating)}`;
}

export function formatPoints(points: number): string {
  return points.toFixed(points % 1 === 0 ? 0 : 1);
}
