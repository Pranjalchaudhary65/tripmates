import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord } from './accessGrant';
import { apiClient } from './client';

export const TIERS = ['NONE', 'EMAIL', 'PHONE', 'SELFIE_MATCH'] as const;
export const DEDUCTION_TYPES = [
  'MEETUP_NO_SHOW',
  'MEETUP_LATE_CANCEL',
  'SETTLEMENT_OVERDUE',
  'CHAT_MESSAGE_FLAGGED',
  'VOUCH_PENALIZED',
] as const;
export const WINDOW_STATUSES = ['NOT_REVIEWABLE', 'NOT_YET', 'OPEN', 'CLOSED'] as const;
export const PERSON_STATUSES = ['TO_WRITE', 'WAITING_FOR_THEM', 'REVEALED', 'NOT_WRITTEN'] as const;
export const REPORT_REASONS = ['HARASSMENT', 'SCAM_OR_MONEY_REQUEST', 'FAKE_PROFILE', 'UNSAFE_BEHAVIOUR', 'OTHER'] as const;

export type DeductionType = (typeof DEDUCTION_TYPES)[number];
export type ReportReason = (typeof REPORT_REASONS)[number];

export interface Component {
  /** 0 to 100. */
  value: number;
  /** 0 to 1. */
  weight: number;
  /** value × weight, what it adds to the score. */
  points: number;
}

export interface Deduction {
  type: DeductionType;
  detail: string | null;
  occurredAt: string;
  fullPoints: number;
  nowPoints: number;
}

export interface TrustBreakdown {
  formulaVersion: string;
  verification: Component & {
    tier: (typeof TIERS)[number];
    tierPoints: number;
    activeVouchers: number;
    vouchBonus: number;
  };
  peerRating: Component & { visibleReviews: number; averageStars: number | null; weightedStars: number };
  completion: Component & { finishedTrips: number };
  reliability: Component & { deductions: Deduction[] };
  vouchPenalty: { count: number; points: number };
}

/** TrustScoreResponse: the caller's own score. Pending until the first computation. */
export type TrustScore =
  | { pending: true }
  | { pending: false; score: number; computedAt: string; breakdown: TrustBreakdown };

export interface TripRef {
  id: string;
  title: string;
  destinationName: string;
  startDate: string;
  endDate: string;
}

export interface WrittenReview {
  rating: number;
  comment: string | null;
  at: string;
}

export interface ReviewPerson {
  userId: string;
  firstName: string;
  status: (typeof PERSON_STATUSES)[number];
  mine: WrittenReview | null;
  /** Only once visible: never while the double-blind still hides it. */
  theirs: WrittenReview | null;
}

export interface TripReviews {
  trip: TripRef;
  window: { status: (typeof WINDOW_STATUSES)[number]; opensOn: string; closesAt: string };
  people: ReviewPerson[];
}

export interface MyReviews {
  toReview: { trip: TripRef; closesAt: string; waiting: number }[];
  received: {
    tripId: string;
    tripTitle: string;
    destinationName: string;
    reviewerFirstName: string;
    rating: number;
    comment: string | null;
    visibleAt: string;
  }[];
}

/** Who a block or report is about: a match, or a trip and someone who travelled on it. */
export type PersonTarget = { matchId: string } | { tripId: string; userId: string };

export interface Block {
  id: string;
  firstName: string;
  since: string;
}

export function createTrustApi(client: AxiosInstance) {
  const trip = (tripId: string) => `/trips/${encodeURIComponent(tripId)}`;
  return {
    async myScore(): Promise<TrustScore> {
      return readTrustScore((await client.get<unknown>('/users/me/trust-score')).data);
    },

    async tripReviews(tripId: string): Promise<TripReviews> {
      return readTripReviews((await client.get<unknown>(`${trip(tripId)}/reviews`)).data);
    },

    async review(tripId: string, revieweeId: string, rating: number, comment: string): Promise<TripReviews> {
      const body = { revieweeId, rating, comment: comment.trim() === '' ? null : comment.trim() };
      return readTripReviews((await client.post<unknown>(`${trip(tripId)}/reviews`, body)).data);
    },

    async myReviews(): Promise<MyReviews> {
      return readMyReviews((await client.get<unknown>('/users/me/reviews')).data);
    },

    async block(target: PersonTarget): Promise<Block> {
      return readBlock((await client.post<unknown>('/blocks', target)).data);
    },

    async blocks(): Promise<Block[]> {
      const raw = (await client.get<unknown>('/blocks')).data;
      if (isRecord(raw) && Array.isArray(raw['items'])) {
        return raw['items'].map(readBlock);
      }
      throw new Error('Unexpected block list.');
    },

    async unblock(blockId: string): Promise<void> {
      await client.delete(`/blocks/${encodeURIComponent(blockId)}`);
    },

    async report(target: PersonTarget, reason: ReportReason, details: string): Promise<void> {
      await client.post('/reports', { ...target, reason, details: details.trim() === '' ? null : details.trim() });
    },
  };
}

export type TrustApi = ReturnType<typeof createTrustApi>;

export const trustApi: TrustApi = createTrustApi(apiClient);

/**
 * Accepted only when it adds up: every component in range, its points equal
 * to value × weight, and the points (less any vouch penalty) equal to the
 * score. A breakdown that does not explain its own score is not shown.
 */
export function readTrustScore(raw: unknown): TrustScore {
  if (isRecord(raw) && raw['pending'] === true && raw['score'] === null && raw['breakdown'] === null) {
    return { pending: true };
  }
  if (isRecord(raw) && raw['pending'] === false && isScore(raw['score']) && isInstant(raw['computedAt'])) {
    const breakdown = readBreakdown(raw['breakdown']);
    const sum =
      breakdown.verification.points +
      breakdown.peerRating.points +
      breakdown.completion.points +
      breakdown.reliability.points -
      breakdown.vouchPenalty.points;
    if (Math.abs(Math.min(100, Math.max(0, sum)) - raw['score']) < 0.011) {
      return { pending: false, score: raw['score'], computedAt: raw['computedAt'], breakdown };
    }
  }
  throw new Error('Unexpected trust score.');
}

function readBreakdown(raw: unknown): TrustBreakdown {
  if (isRecord(raw) && isNonEmptyString(raw['formulaVersion'])) {
    const verification = raw['verification'];
    const peer = raw['peerRating'];
    const completion = raw['completion'];
    const reliability = raw['reliability'];
    const penalty = raw['vouchPenalty'];
    if (
      isRecord(verification) &&
      isRecord(peer) &&
      isRecord(completion) &&
      isRecord(reliability) &&
      isRecord(penalty) &&
      isCount(verification['tierPoints']) &&
      isCount(verification['activeVouchers']) &&
      isCount(verification['vouchBonus']) &&
      verification['vouchBonus'] <= 10 &&
      isCount(peer['visibleReviews']) &&
      (peer['averageStars'] === null || isStars(peer['averageStars'])) &&
      isStars(peer['weightedStars']) &&
      isCount(completion['finishedTrips']) &&
      Array.isArray(reliability['deductions']) &&
      isCount(penalty['count']) &&
      isNumberIn(penalty['points'], 0, 9)
    ) {
      const tier = oneOf(TIERS, verification['tier']);
      if (tier !== null) {
        return {
          formulaVersion: raw['formulaVersion'],
          verification: {
            ...readComponent(verification),
            tier,
            tierPoints: verification['tierPoints'],
            activeVouchers: verification['activeVouchers'],
            vouchBonus: verification['vouchBonus'],
          },
          peerRating: {
            ...readComponent(peer),
            visibleReviews: peer['visibleReviews'],
            averageStars: peer['averageStars'],
            weightedStars: peer['weightedStars'],
          },
          completion: { ...readComponent(completion), finishedTrips: completion['finishedTrips'] },
          reliability: { ...readComponent(reliability), deductions: reliability['deductions'].map(readDeduction) },
          vouchPenalty: { count: penalty['count'], points: penalty['points'] },
        };
      }
    }
  }
  throw new Error('Unexpected trust breakdown.');
}

function readComponent(raw: Record<string, unknown>): Component {
  const { value, weight, points } = raw;
  if (isScore(value) && isNumberIn(weight, 0, 1) && isNumberIn(points, 0, 100) && Math.abs(value * weight - points) < 0.011) {
    return { value, weight, points };
  }
  throw new Error('Unexpected trust component.');
}

function readDeduction(raw: unknown): Deduction {
  if (
    isRecord(raw) &&
    (raw['detail'] === null || (typeof raw['detail'] === 'string' && /^[A-Z_]{1,30}$/.test(raw['detail']))) &&
    isInstant(raw['occurredAt']) &&
    isNumberIn(raw['fullPoints'], 0, 100) &&
    isNumberIn(raw['nowPoints'], 0, raw['fullPoints'])
  ) {
    const type = oneOf(DEDUCTION_TYPES, raw['type']);
    if (type !== null) {
      return {
        type,
        detail: raw['detail'],
        occurredAt: raw['occurredAt'],
        fullPoints: raw['fullPoints'],
        nowPoints: raw['nowPoints'],
      };
    }
  }
  throw new Error('Unexpected deduction.');
}

/**
 * Accepted only when the double-blind holds: nobody's review of the caller
 * appears unless the caller's own review of them is visible too or the
 * window has closed, and nothing is offered to write outside an open window.
 */
export function readTripReviews(raw: unknown): TripReviews {
  if (isRecord(raw) && isRecord(raw['window']) && Array.isArray(raw['people'])) {
    const window = raw['window'];
    const status = oneOf(WINDOW_STATUSES, window['status']);
    if (status !== null && isDate(window['opensOn']) && isInstant(window['closesAt'])) {
      const people = raw['people'].map((person: unknown) => readPerson(person, status));
      return {
        trip: readTripRef(raw['trip']),
        window: { status, opensOn: window['opensOn'], closesAt: window['closesAt'] },
        people,
      };
    }
  }
  throw new Error('Unexpected reviews.');
}

function readPerson(raw: unknown, window: TripReviews['window']['status']): ReviewPerson {
  if (isRecord(raw) && isNonEmptyString(raw['userId']) && typeof raw['firstName'] === 'string') {
    const status = oneOf(PERSON_STATUSES, raw['status']);
    const mine = raw['mine'] === null ? null : readWritten(raw['mine']);
    const theirs = raw['theirs'] === null ? null : readWritten(raw['theirs']);
    const consistent =
      status !== null &&
      (status === 'TO_WRITE' ? mine === null && window === 'OPEN' : true) &&
      (status === 'NOT_WRITTEN' ? mine === null && window !== 'OPEN' : true) &&
      (status === 'WAITING_FOR_THEM' ? mine !== null && theirs === null : true) &&
      (status === 'REVEALED' ? mine !== null : true) &&
      (theirs === null || status === 'REVEALED' || window === 'CLOSED');
    if (consistent) {
      return { userId: raw['userId'], firstName: raw['firstName'], status, mine, theirs };
    }
  }
  throw new Error('Unexpected reviewee.');
}

function readWritten(raw: unknown): WrittenReview {
  if (
    isRecord(raw) &&
    isStarRating(raw['rating']) &&
    (raw['comment'] === null || (typeof raw['comment'] === 'string' && raw['comment'].length <= 500)) &&
    isInstant(raw['at'])
  ) {
    return { rating: raw['rating'], comment: raw['comment'], at: raw['at'] };
  }
  throw new Error('Unexpected review.');
}

function readTripRef(raw: unknown): TripRef {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['title']) &&
    isNonEmptyString(raw['destinationName']) &&
    isDate(raw['startDate']) &&
    isDate(raw['endDate'])
  ) {
    return {
      id: raw['id'],
      title: raw['title'],
      destinationName: raw['destinationName'],
      startDate: raw['startDate'],
      endDate: raw['endDate'],
    };
  }
  throw new Error('Unexpected trip.');
}

export function readMyReviews(raw: unknown): MyReviews {
  if (isRecord(raw) && Array.isArray(raw['toReview']) && Array.isArray(raw['received'])) {
    return {
      toReview: raw['toReview'].map((item: unknown) => {
        if (isRecord(item) && isInstant(item['closesAt']) && isCount(item['waiting']) && item['waiting'] > 0) {
          return { trip: readTripRef(item['trip']), closesAt: item['closesAt'], waiting: item['waiting'] };
        }
        throw new Error('Unexpected trip to review.');
      }),
      received: raw['received'].map((item: unknown) => {
        if (
          isRecord(item) &&
          isNonEmptyString(item['tripId']) &&
          isNonEmptyString(item['tripTitle']) &&
          isNonEmptyString(item['destinationName']) &&
          typeof item['reviewerFirstName'] === 'string' &&
          isStarRating(item['rating']) &&
          (item['comment'] === null || typeof item['comment'] === 'string') &&
          isInstant(item['visibleAt'])
        ) {
          return {
            tripId: item['tripId'],
            tripTitle: item['tripTitle'],
            destinationName: item['destinationName'],
            reviewerFirstName: item['reviewerFirstName'],
            rating: item['rating'],
            comment: item['comment'],
            visibleAt: item['visibleAt'],
          };
        }
        throw new Error('Unexpected received review.');
      }),
    };
  }
  throw new Error('Unexpected reviews.');
}

function readBlock(raw: unknown): Block {
  if (isRecord(raw) && isNonEmptyString(raw['id']) && typeof raw['firstName'] === 'string' && isInstant(raw['since'])) {
    return { id: raw['id'], firstName: raw['firstName'], since: raw['since'] };
  }
  throw new Error('Unexpected block.');
}

function isScore(value: unknown): value is number {
  return isNumberIn(value, 0, 100);
}

function isStars(value: unknown): value is number {
  return isNumberIn(value, 1, 5);
}

function isStarRating(value: unknown): value is number {
  return Number.isInteger(value) && isStars(value);
}

function isCount(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0;
}

function isNumberIn(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value));
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
