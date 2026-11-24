import { describe, expect, it } from 'vitest';
import { validateComfort, validateEmergencyContact } from '../validation/meetupRules';
import { distanceMeters, midpoint } from './geo';
import { startTimes } from './meetupLabels';

describe('geometry', () => {
  it('measures short distances to within a metre', () => {
    const venue = { latitude: 14.5402, longitude: 74.3167 };
    expect(distanceMeters(venue, { latitude: 14.5411, longitude: 74.3167 })).toBeCloseTo(100, 0);
  });

  it('finds the halfway point between two cities', () => {
    const bengaluru = { latitude: 12.9716, longitude: 77.5946 };
    const mumbai = { latitude: 19.076, longitude: 72.8777 };
    const half = midpoint(bengaluru, mumbai);

    expect(distanceMeters(bengaluru, half)).toBeCloseTo(distanceMeters(half, mumbai), -2);
  });
});

describe('start times', () => {
  it('offers every quarter hour in a window, both ends included', () => {
    expect(startTimes('2026-12-05T06:00:00Z', '2026-12-05T06:45:00Z')).toEqual([
      '2026-12-05T06:00:00.000Z',
      '2026-12-05T06:15:00.000Z',
      '2026-12-05T06:30:00.000Z',
      '2026-12-05T06:45:00.000Z',
    ]);
  });
});

describe('meet-up form rules', () => {
  it('needs a name, a relationship and a plausible number', () => {
    expect(validateEmergencyContact({ name: '', relationship: '', phoneNumber: '12' })).toEqual({
      name: 'Enter their name.',
      relationship: 'Choose who they are to you.',
      phoneNumber: 'Enter a mobile number with its country code, such as +91 98765 43210.',
    });
    expect(validateEmergencyContact({ name: 'Meera', relationship: 'SIBLING', phoneNumber: '+91 98765 43210' })).toEqual({});
    expect(validateEmergencyContact({ name: '<b>x</b>', relationship: 'FRIEND', phoneNumber: '9876543210' }).name).toBeDefined();
  });

  it('needs a rating of 1 to 5', () => {
    expect(validateComfort(null, '')).toBe('Choose how comfortable you felt.');
    expect(validateComfort(3, '')).toBeNull();
    expect(validateComfort(3, 'x'.repeat(1001))).toBe('Keep notes under 1000 characters.');
  });
});
