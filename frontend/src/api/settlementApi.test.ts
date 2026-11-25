import { describe, expect, it } from 'vitest';
import { expense, ledger, payment, settlement } from '../test/settlementFixtures';
import { readLedger } from './settlementApi';

describe('readLedger', () => {
  it('accepts a ledger that agrees with itself', () => {
    const read = readLedger(ledger());

    expect(read.myNetPaise).toBe(-120_000);
    expect(read.settlements[0]?.payment?.upiLink).toMatch(/^upi:\/\/pay\?/);
  });

  it('refuses balances that do not add up to zero', () => {
    const members = ledger().members.map((member, index) => (index === 0 ? { ...member, netPaise: 1 } : member));
    expect(() => readLedger(ledger({ members }))).toThrow();
  });

  it('refuses amounts that are not whole paise', () => {
    expect(() => readLedger(ledger({ settlements: [settlement({ amountPaise: 1200.5 })] }))).toThrow();
    expect(() => readLedger(ledger({ totalSpentPaise: -1 }))).toThrow();
  });

  it('refuses a payment link that is not exactly the server’s upi://pay shape', () => {
    for (const upiLink of [
      'javascript:alert(1)',
      'https://pay.example/asha',
      'upi://pay?pa=asha.rao@okbank&pn=Asha&am=1200.00&cu=INR&tn=x&pa=thief@bank',
      'upi://pay?pa=thief@bank&pn=Asha&am=1200.00&cu=INR&tn=x',
      'upi://pay?pa=asha.rao@okbank&pn=Asha&am=1.00&cu=INR&tn=x',
    ]) {
      expect(() => readLedger(ledger({ settlements: [settlement({ payment: payment({ upiLink }) })] })), upiLink).toThrow();
    }
  });

  it('refuses actions offered to someone they do not belong to', () => {
    expect(() =>
      readLedger(ledger({ settlements: [settlement({ myPart: 'NONE', canMarkPaid: true, payment: null })] })),
    ).toThrow();
    expect(() => readLedger(ledger({ settlements: [settlement({ canConfirm: true })] }))).toThrow();
    expect(() => readLedger(ledger({ settlements: [settlement({ myPart: 'PAYEE', canMarkPaid: false })] }))).toThrow();
  });

  it('refuses an expense whose shares do not add up to it', () => {
    const shares = expense().shares.slice(1);
    expect(() => readLedger(ledger({ recentExpenses: [expense({ shares })] }))).toThrow();
  });

  it('refuses expenses open to the ledger when the trip is not locked', () => {
    expect(() => readLedger(ledger({ locked: false, open: true }))).toThrow();
  });
});
