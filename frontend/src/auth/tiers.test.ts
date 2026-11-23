import { describe, expect, it } from 'vitest';
import { tierAtLeast } from './tiers';

describe('tierAtLeast', () => {
  it('orders the tiers from none to selfie match', () => {
    expect(tierAtLeast('SELFIE_MATCH', 'SELFIE_MATCH')).toBe(true);
    expect(tierAtLeast('SELFIE_MATCH', 'PHONE')).toBe(true);
    expect(tierAtLeast('PHONE', 'SELFIE_MATCH')).toBe(false);
    expect(tierAtLeast('EMAIL', 'PHONE')).toBe(false);
    expect(tierAtLeast('NONE', 'EMAIL')).toBe(false);
  });
});
