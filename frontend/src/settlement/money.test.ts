import { describe, expect, it } from 'vitest';
import { equalShares, formatPaise, rupeesToPaise } from './money';

describe('rupeesToPaise', () => {
  it('reads rupees as whole paise without floating point', () => {
    expect(rupeesToPaise('1800')).toBe(180_000);
    expect(rupeesToPaise('1,800.5')).toBe(180_050);
    expect(rupeesToPaise('0.29')).toBe(29);
    expect(rupeesToPaise(' 12.05 ')).toBe(1205);
  });

  it('refuses anything that is not a plain positive amount', () => {
    for (const text of ['', '0', '0.00', '-5', '12.345', '1e3', 'abc', '12.', '.5']) {
      expect(rupeesToPaise(text), text).toBeNull();
    }
  });
});

describe('formatPaise', () => {
  it('shows rupees with Indian grouping', () => {
    expect(formatPaise(10_000_050)).toBe('₹1,00,000.50');
  });
});

describe('equalShares', () => {
  it('splits like the server: leftover paise to the first IDs in order', () => {
    const shares = equalShares(100, ['c', 'a', 'b']);
    expect([...shares.entries()]).toEqual([
      ['a', 34],
      ['b', 33],
      ['c', 33],
    ]);
  });
});
