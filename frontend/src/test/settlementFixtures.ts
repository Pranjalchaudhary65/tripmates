import { SIGNED_IN_USER_ID } from './renderApp';
import { TRIP_ID } from './tripFixtures';

export { TRIP_ID };

export const HOST_ID = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
export const OTHER_ID = '2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e';
export const SETTLEMENT_ID = '3c4d5e6f-7a8b-4c9d-8e1f-2a3b4c5d6e7f';
export const EXPENSE_ID = '4d5e6f7a-8b9c-4dae-9f20-3b4c5d6e7f80';

const asha = { userId: HOST_ID, firstName: 'Asha' };
const me = { userId: SIGNED_IN_USER_ID, firstName: 'Ravi' };
const meera = { userId: OTHER_ID, firstName: 'Meera' };

export function payment(overrides: Record<string, unknown> = {}) {
  return {
    upiLink: 'upi://pay?pa=asha.rao@okbank&pn=Asha&am=1200.00&cu=INR&tn=TripMates%20settle-up',
    payeeUpiId: 'asha.rao@okbank',
    upiChangedRecently: false,
    ...overrides,
  };
}

/** The caller (Ravi) owes host Asha ₹1,200, as the PROPOSED payer, with Asha's UPI link. */
export function settlement(overrides: Record<string, unknown> = {}) {
  return {
    id: SETTLEMENT_ID,
    from: me,
    to: asha,
    amountPaise: 120_000,
    status: 'PROPOSED',
    createdAt: '2026-12-15T10:00:00Z',
    markedPaidAt: null,
    confirmedAt: null,
    myPart: 'PAYER',
    canMarkPaid: true,
    canConfirm: false,
    canDispute: true,
    payment: payment(),
    ...overrides,
  };
}

export function expense(overrides: Record<string, unknown> = {}) {
  return {
    id: EXPENSE_ID,
    description: 'Homestay, two nights',
    amountPaise: 360_000,
    paidBy: asha,
    createdAt: '2026-12-14T09:00:00Z',
    shares: [
      { person: asha, sharePaise: 120_000 },
      { person: meera, sharePaise: 120_000 },
      { person: me, sharePaise: 120_000 },
    ],
    reversed: false,
    canReverse: false,
    ...overrides,
  };
}

/** LedgerResponse: a locked trip of three where Asha paid ₹3,600 for everyone. */
export function ledger(overrides: Record<string, unknown> = {}) {
  return {
    trip: {
      id: TRIP_ID,
      title: 'Monsoon weekend in Gokarna',
      destinationName: 'Gokarna, Karnataka',
      startDate: '2026-12-12',
      endDate: '2026-12-14',
    },
    locked: true,
    open: true,
    currency: 'INR',
    members: [
      { ...asha, role: 'HOST', onTrip: true, me: false, netPaise: 240_000 },
      { ...meera, role: 'COMPANION', onTrip: true, me: false, netPaise: -120_000 },
      { ...me, role: 'COMPANION', onTrip: true, me: true, netPaise: -120_000 },
    ],
    myNetPaise: -120_000,
    totalSpentPaise: 360_000,
    settlements: [
      settlement(),
      settlement({
        id: '5e6f7a8b-9cad-4ebf-8031-4c5d6e7f8091',
        from: meera,
        myPart: 'NONE',
        canMarkPaid: false,
        canDispute: false,
        payment: null,
      }),
    ],
    recentExpenses: [expense()],
    settledUp: false,
    ...overrides,
  };
}

export function historyPage(items: unknown[], overrides: Record<string, unknown> = {}) {
  return { items, page: 0, size: 20, totalItems: items.length, totalPages: 1, ...overrides };
}
