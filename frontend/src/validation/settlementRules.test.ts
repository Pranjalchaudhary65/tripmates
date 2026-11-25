import { describe, expect, it } from 'vitest';
import { validateExpense, validateUpiId } from './settlementRules';

const ME = 'me';

function draft(overrides: Partial<Parameters<typeof validateExpense>[0]> = {}) {
  return {
    description: 'Taxi',
    amount: '600',
    split: 'EQUAL' as const,
    participants: new Map([
      [ME, ''],
      ['other', ''],
    ]),
    ...overrides,
  };
}

describe('validateExpense', () => {
  it('accepts an equal split with someone else in it', () => {
    expect(validateExpense(draft(), ME)).toEqual({});
  });

  it('needs a description, a positive amount and someone else', () => {
    const errors = validateExpense(draft({ description: ' ', amount: '0', participants: new Map([[ME, '']]) }), ME);
    expect(Object.keys(errors).sort()).toEqual(['amountPaise', 'description', 'participants']);
  });

  it('needs exact shares that add up to the amount', () => {
    const split = 'EXACT' as const;
    expect(validateExpense(draft({ split, participants: new Map([[ME, '200'], ['other', '400']]) }), ME)).toEqual({});
    expect(validateExpense(draft({ split, participants: new Map([[ME, '200'], ['other', '300']]) }), ME).participants).toMatch(
      /add up/,
    );
    expect(validateExpense(draft({ split, participants: new Map([[ME, '600'], ['other', '']]) }), ME).participants).toMatch(
      /more than zero/,
    );
  });

  it('refuses more than ten lakh rupees', () => {
    expect(validateExpense(draft({ amount: '1000000.01' }), ME).amountPaise).toBeDefined();
  });
});

describe('validateUpiId', () => {
  it('accepts name@bank and refuses anything that could break a link', () => {
    expect(validateUpiId('Asha.Rao@OkBank')).toBeUndefined();
    expect(validateUpiId('asha@okbank&am=1')).toBeDefined();
    expect(validateUpiId('asha')).toBeDefined();
  });
});
