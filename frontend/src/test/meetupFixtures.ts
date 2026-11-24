import { MATCH_ID } from './chatFixtures';
import { TRIP_ID } from './tripFixtures';

export { MATCH_ID };

export const VENUE_ID = '6d7e8f90-a1b2-4d3e-8f40-5b6c7d8e9f00';

export function venue(overrides: Record<string, unknown> = {}) {
  return {
    id: VENUE_ID,
    name: 'Gokarna bus stand',
    address: 'Car Street',
    city: 'Gokarna',
    category: 'TRANSIT_HUB',
    latitude: 14.5402,
    longitude: 74.3167,
    timeZone: 'Asia/Kolkata',
    ...overrides,
  };
}

export function meetup(overrides: Record<string, unknown> = {}) {
  return {
    id: '7e8f90a1-b2c3-4e4f-9051-6c7d8e9f0011',
    venue: venue(),
    scheduledAt: '2026-12-05T06:30:00Z',
    status: 'PROPOSED',
    proposedByMe: false,
    myPlusOne: false,
    theirPlusOne: false,
    checkInOpensAt: '2026-12-05T06:00:00Z',
    checkInClosesAt: '2026-12-05T08:00:00Z',
    iArrived: false,
    theyArrived: false,
    iConfirmedSafe: false,
    cancellation: null,
    ...overrides,
  };
}

/** MatchResponse: the caller is the companion of host Asha, at the stage given. */
export function match(stage: string, overrides: Record<string, unknown> = {}) {
  return {
    matchId: MATCH_ID,
    myRole: 'COMPANION',
    trip: { id: TRIP_ID, title: 'Monsoon weekend in Gokarna', destinationName: 'Gokarna, Karnataka', startDate: '2026-12-12' },
    otherPerson: { firstName: 'Asha', verificationTier: 'PHONE' },
    stage,
    videoCall: stage === 'DID_NOT_PROCEED' ? null : { confirmedByMe: true, confirmedByThem: true, verified: true },
    emergencyContactSet: true,
    meetup: null,
    myFeedbackGiven: false,
    ...overrides,
  };
}

export function venueOptions() {
  return {
    venues: [
      {
        venue: venue(),
        distanceKm: 0.8,
        timeZone: 'Asia/Kolkata',
        windows: [
          { date: '2026-12-05', earliest: '2026-12-05T01:45:00Z', latest: '2026-12-05T10:15:00Z' },
          { date: '2026-12-06', earliest: '2026-12-06T01:45:00Z', latest: '2026-12-06T10:15:00Z' },
        ],
      },
    ],
  };
}
