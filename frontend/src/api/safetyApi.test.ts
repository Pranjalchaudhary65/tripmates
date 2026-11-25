import { describe, expect, it } from 'vitest';
import { liveTrip, premortem } from '../test/safetyFixtures';
import { readLiveTrip, readPremortem } from './safetyApi';

describe('readLiveTrip', () => {
  it('accepts a live trip that agrees with itself', () => {
    expect(readLiveTrip(liveTrip()).sharing?.checkInEveryHours).toBe(12);
  });

  it('refuses a traveller that carries a location', () => {
    const buddies = [{ firstName: 'Asha', role: 'HOST', sharing: true, lastCheckInAt: null, latitude: 14.55, longitude: 74.31 }];
    expect(() => readLiveTrip(liveTrip({ buddies }))).toThrow();
  });

  it('refuses a check-in due while nothing is shared, or sharing on a trip that is not locked', () => {
    expect(() => readLiveTrip(liveTrip({ sharing: null }))).toThrow();
    expect(() => readLiveTrip(liveTrip({ locked: false }))).toThrow();
  });

  it('refuses an interval the server never offers', () => {
    expect(() =>
      readLiveTrip(liveTrip({ sharing: { checkInEveryHours: 5, noteSet: false, startedAt: '2026-12-12T03:00:00Z' } })),
    ).toThrow();
  });
});

describe('readPremortem', () => {
  it('refuses topics for a pair that could not have been compared', () => {
    expect(readPremortem(premortem()).pairs[0]?.topics).toHaveLength(1);
    expect(() => readPremortem(premortem({ myVibeSet: false }))).toThrow();
  });
});
