import { TRIP_ID } from './tripFixtures';

export { TRIP_ID };

export const SOS_ID = '6a7b8c9d-0e1f-4a2b-8c3d-4e5f6a7b8c9d';

/** LiveTripResponse: a locked trip under way, the caller sharing with a check-in due later today. */
export function liveTrip(overrides: Record<string, unknown> = {}) {
  return {
    trip: {
      id: TRIP_ID,
      title: 'Monsoon weekend in Gokarna',
      destinationName: 'Gokarna, Karnataka',
      destinationLatitude: 14.5479,
      destinationLongitude: 74.3188,
      startDate: '2026-12-12',
      endDate: '2026-12-14',
    },
    phase: 'ACTIVE',
    locked: true,
    emergencyContactSet: true,
    sharing: { checkInEveryHours: 12, noteSet: false, startedAt: '2026-12-12T03:00:00Z' },
    nextCheckIn: { dueAt: '2026-12-12T15:00:00Z', overdue: false },
    lastCheckIn: null,
    buddies: [{ firstName: 'Asha', role: 'HOST', sharing: true, lastCheckInAt: '2026-12-12T09:00:00Z' }],
    openSos: null,
    ...overrides,
  };
}

export function premortem(overrides: Record<string, unknown> = {}) {
  return {
    tripId: TRIP_ID,
    startDate: '2026-12-12',
    locked: true,
    myVibeSet: true,
    pairs: [
      {
        firstName: 'Asha',
        vibeSet: true,
        topics: [
          {
            dimension: 'PACE',
            gap: 'You like slow days; Asha likes packed days.',
            question: 'Decide which days are full and which are free, and that splitting up for a day is fine.',
          },
        ],
      },
    ],
    ...overrides,
  };
}
