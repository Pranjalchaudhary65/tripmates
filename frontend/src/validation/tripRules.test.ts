import { describe, expect, it } from 'vitest';
import { addDays, dateWindowError, daysBetween, hasForbiddenCharacter, tripToday, validateTrip, type TripForm } from './tripRules';

const TODAY = '2026-11-24';

function form(overrides: Partial<TripForm> = {}): TripForm {
  return {
    title: '  Monsoon weekend  ',
    description: 'Beach hopping.\nHostel stays.',
    destinationName: 'Gokarna',
    latitude: '14.5479',
    longitude: '74.3188',
    radiusKm: '25',
    startDate: '2026-12-12',
    endDate: '2026-12-14',
    totalSlots: '3',
    budgetBand: 'MID',
    ...overrides,
  };
}

describe('trip rules', () => {
  it('builds the request body from a valid form, trimmed and typed', () => {
    const { errors, body } = validateTrip(form(), TODAY);

    expect(errors).toEqual({});
    expect(body).toEqual({
      title: 'Monsoon weekend',
      description: 'Beach hopping.\nHostel stays.',
      destinationName: 'Gokarna',
      latitude: 14.5479,
      longitude: 74.3188,
      radiusKm: 25,
      startDate: '2026-12-12',
      endDate: '2026-12-14',
      totalSlots: 3,
      budgetBand: 'MID',
    });
  });

  it.each([
    ['title', { title: 'Hi' }],
    ['title', { title: 'Hi‮there' }],
    ['title', { title: 'Bell\u0007' }],
    ['description', { description: '   ' }],
    ['description', { description: 'x'.repeat(4001) }],
    ['destinationName', { destinationName: '' }],
    ['latitude', { latitude: '91' }],
    ['latitude', { latitude: '' }],
    ['longitude', { longitude: '1e2' }],
    ['radiusKm', { radiusKm: '501' }],
    ['radiusKm', { radiusKm: '2.345' }],
    ['startDate', { startDate: '2026-11-23', endDate: '2026-11-25' }],
    ['startDate', { startDate: addDays(TODAY, 366), endDate: addDays(TODAY, 367) }],
    ['endDate', { endDate: '2026-12-11' }],
    ['endDate', { endDate: addDays('2026-12-12', 61) }],
    ['totalSlots', { totalSlots: '0' }],
    ['totalSlots', { totalSlots: '21' }],
    ['totalSlots', { totalSlots: '2.5' }],
  ] as const)('refuses a bad %s', (field, overrides) => {
    const { errors, body } = validateTrip(form(overrides), TODAY);

    expect(body).toBeNull();
    expect(Object.keys(errors)).toEqual([field]);
  });

  it('accepts the edges the server accepts', () => {
    expect(validateTrip(form({ startDate: TODAY, endDate: addDays(TODAY, 60) }), TODAY).body).not.toBeNull();
    expect(validateTrip(form({ radiusKm: '' }), TODAY).body?.radiusKm).toBe(0);
    expect(validateTrip(form({ totalSlots: '20', radiusKm: '500' }), TODAY).body).not.toBeNull();
  });

  it('allows line breaks in a description but not in single-line text', () => {
    expect(hasForbiddenCharacter('one\ntwo\tthree', true)).toBe(false);
    expect(hasForbiddenCharacter('one\ntwo', false)).toBe(true);
    expect(hasForbiddenCharacter('mark⁦', true)).toBe(true);
    expect(hasForbiddenCharacter('café ☕', false)).toBe(false);
  });

  it('counts days across month and year ends', () => {
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(daysBetween('2026-12-25', '2027-01-04')).toBe(10);
  });

  it('takes today from India, not from the browser zone', () => {
    expect(tripToday(new Date('2026-11-23T20:00:00Z'))).toBe('2026-11-24');
  });

  it('refuses a backwards date window only', () => {
    expect(dateWindowError('2026-12-10', '2026-12-01')).not.toBeNull();
    expect(dateWindowError('2026-12-10', '')).toBeNull();
    expect(dateWindowError('2026-12-10', '2026-12-10')).toBeNull();
  });
});
