import { TRIP_ID } from './tripFixtures';

export { TRIP_ID };

export const ASHA_ID = '1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d';
export const BLOCK_ID = '7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f';

/** TrustScoreResponse for a phone-verified account with one no-show; the points add up to the score. */
export function trustScore(overrides: Record<string, unknown> = {}) {
  return {
    pending: false,
    score: 57.26,
    computedAt: '2026-11-25T10:00:00Z',
    breakdown: {
      formulaVersion: 'v1',
      verification: { tier: 'PHONE', tierPoints: 25, activeVouchers: 0, vouchBonus: 0, value: 41.7, weight: 0.3, points: 12.51 },
      peerRating: { visibleReviews: 0, averageStars: null, weightedStars: 3.5, value: 62.5, weight: 0.3, points: 18.75 },
      completion: { finishedTrips: 0, value: 50.0, weight: 0.2, points: 10.0 },
      reliability: {
        deductions: [
          { type: 'MEETUP_NO_SHOW', detail: null, occurredAt: '2026-11-20T10:00:00Z', fullPoints: 20.0, nowPoints: 20.0 },
        ],
        value: 80.0,
        weight: 0.2,
        points: 16.0,
      },
      vouchPenalty: { count: 0, points: 0 },
    },
    ...overrides,
  };
}

/** TripReviewsResponse: an ended trip, the window open, one fellow traveller not reviewed yet. */
export function tripReviews(person: Record<string, unknown> = {}, windowOverrides: Record<string, unknown> = {}) {
  return {
    trip: {
      id: TRIP_ID,
      title: 'Monsoon weekend in Gokarna',
      destinationName: 'Gokarna, Karnataka',
      startDate: '2026-11-18',
      endDate: '2026-11-20',
    },
    window: { status: 'OPEN', opensOn: '2026-11-21', closesAt: '2026-12-04T18:30:00Z', ...windowOverrides },
    people: [{ userId: ASHA_ID, firstName: 'Asha', status: 'TO_WRITE', mine: null, theirs: null, ...person }],
  };
}

export function myReviews(overrides: Record<string, unknown> = {}) {
  return {
    toReview: [
      {
        trip: {
          id: TRIP_ID,
          title: 'Monsoon weekend in Gokarna',
          destinationName: 'Gokarna, Karnataka',
          startDate: '2026-11-18',
          endDate: '2026-11-20',
        },
        closesAt: '2026-12-04T18:30:00Z',
        waiting: 1,
      },
    ],
    received: [],
    ...overrides,
  };
}
