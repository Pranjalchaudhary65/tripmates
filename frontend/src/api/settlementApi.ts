import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord } from './accessGrant';
import { apiClient } from './client';

export const SETTLEMENT_STATUSES = ['PROPOSED', 'MARKED_PAID', 'CONFIRMED', 'DISPUTED'] as const;
export const LEDGER_KINDS = ['EXPENSE', 'EXPENSE_REVERSAL', 'SETTLEMENT'] as const;
const PARTS = ['PAYER', 'PAYEE', 'NONE'] as const;
const ROLES = ['HOST', 'COMPANION'] as const;

export type SettlementStatus = (typeof SETTLEMENT_STATUSES)[number];
export type LedgerKind = (typeof LEDGER_KINDS)[number];
export type SplitMethod = 'EQUAL' | 'EXACT';

/** Whole paise everywhere: never a float, never rupees. */
export type Paise = number;

export interface Person {
  userId: string;
  firstName: string;
}

export interface Member extends Person {
  role: (typeof ROLES)[number];
  /** False for a companion who stepped back after the trip locked. */
  onTrip: boolean;
  me: boolean;
  /** Positive: the others owe them. Negative: they owe. */
  netPaise: Paise;
}

export interface Payment {
  /** A upi://pay link built by the server; checked here before it can become an href. */
  upiLink: string;
  payeeUpiId: string;
  upiChangedRecently: boolean;
}

export interface Settlement {
  id: string;
  from: Person;
  to: Person;
  amountPaise: Paise;
  status: SettlementStatus;
  createdAt: string;
  markedPaidAt: string | null;
  confirmedAt: string | null;
  myPart: (typeof PARTS)[number];
  canMarkPaid: boolean;
  canConfirm: boolean;
  canDispute: boolean;
  /** Only for the payer of an open payment whose payee set a UPI ID. */
  payment: Payment | null;
}

export interface Expense {
  id: string;
  description: string;
  amountPaise: Paise;
  paidBy: Person;
  createdAt: string;
  shares: { person: Person; sharePaise: Paise }[];
  reversed: boolean;
  canReverse: boolean;
}

/** LedgerResponse: a trip's settle-up screen. Obligations between travellers, never money TripMates holds. */
export interface Ledger {
  trip: { id: string; title: string; destinationName: string; startDate: string; endDate: string };
  locked: boolean;
  open: boolean;
  currency: 'INR';
  members: Member[];
  myNetPaise: Paise;
  totalSpentPaise: Paise;
  settlements: Settlement[];
  recentExpenses: Expense[];
  settledUp: boolean;
}

export interface HistoryItem {
  id: string;
  kind: LedgerKind;
  createdAt: string;
  description: string | null;
  amountPaise: Paise;
  paidBy: Person | null;
  from: Person | null;
  to: Person | null;
  reversesId: string | null;
  myEffectPaise: number;
}

export interface HistoryPage {
  items: HistoryItem[];
  page: number;
  totalPages: number;
}

export interface NewExpense {
  clientExpenseId: string;
  description: string;
  amountPaise: Paise;
  split: SplitMethod;
  participants: { userId: string; sharePaise?: Paise }[];
}

export function createSettlementApi(client: AxiosInstance) {
  const ledger = async (request: Promise<{ data: unknown }>) => readLedger((await request).data);
  const trip = (tripId: string) => `/trips/${encodeURIComponent(tripId)}`;
  return {
    ledger: (tripId: string) => ledger(client.get<unknown>(`${trip(tripId)}/ledger`)),

    async history(tripId: string, page: number): Promise<HistoryPage> {
      const response = await client.get<unknown>(`${trip(tripId)}/ledger/history`, { params: { page, size: 20 } });
      return readHistoryPage(response.data);
    },

    addExpense: (tripId: string, expense: NewExpense) => ledger(client.post<unknown>(`${trip(tripId)}/expenses`, expense)),

    reverse: (expenseId: string) =>
      ledger(client.post<unknown>(`/expenses/${encodeURIComponent(expenseId)}/reverse`)),

    markPaid: (settlementId: string) =>
      ledger(client.post<unknown>(`/settlements/${encodeURIComponent(settlementId)}/mark-paid`)),

    confirm: (settlementId: string) =>
      ledger(client.post<unknown>(`/settlements/${encodeURIComponent(settlementId)}/confirm`)),

    dispute: (settlementId: string) =>
      ledger(client.post<unknown>(`/settlements/${encodeURIComponent(settlementId)}/dispute`)),

    async upiId(): Promise<string | null> {
      return readUpiBody((await client.get<unknown>('/users/me/upi-id')).data);
    },

    async setUpiId(upiId: string): Promise<string> {
      const stored = readUpiBody((await client.put<unknown>('/users/me/upi-id', { upiId })).data);
      if (stored === null) {
        throw new Error('Unexpected UPI ID.');
      }
      return stored;
    },

    async removeUpiId(): Promise<void> {
      await client.delete('/users/me/upi-id');
    },
  };
}

export type SettlementApi = ReturnType<typeof createSettlementApi>;

export const settlementApi: SettlementApi = createSettlementApi(apiClient);

/** The server's own UPI ID format; a value outside it is never put into a link or shown. */
export const UPI_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{1,63}@[a-z][a-z0-9]{1,31}$/;

/**
 * Only the exact link shape the server builds: the upi:// scheme, the pay
 * action, a UPI ID in the server's format and a plain amount. Anything else
 * (a javascript: URL, another app's scheme, an extra pa=) fails the load
 * rather than becoming a button.
 */
const UPI_LINK_PATTERN =
  /^upi:\/\/pay\?pa=([a-z0-9][a-z0-9._-]{1,63}@[a-z][a-z0-9]{1,31})&pn=[A-Za-z0-9%._*-]*&am=\d{1,9}\.\d{2}&cu=INR&tn=[A-Za-z0-9%._*-]*$/;

/**
 * A ledger is accepted only when it agrees with itself: the balances add up
 * to zero, amounts are whole paise, and actions are offered only to the
 * person they belong to.
 */
export function readLedger(raw: unknown): Ledger {
  if (
    isRecord(raw) &&
    isRecord(raw['trip']) &&
    typeof raw['locked'] === 'boolean' &&
    typeof raw['open'] === 'boolean' &&
    raw['currency'] === 'INR' &&
    Array.isArray(raw['members']) &&
    Array.isArray(raw['settlements']) &&
    Array.isArray(raw['recentExpenses']) &&
    isSignedPaise(raw['myNetPaise']) &&
    isPaise(raw['totalSpentPaise'], true) &&
    typeof raw['settledUp'] === 'boolean'
  ) {
    const trip = raw['trip'];
    const members = raw['members'].map(readMember);
    const settlements = raw['settlements'].map(readSettlement);
    const sum = members.reduce((total, member) => total + member.netPaise, 0);
    if (
      isNonEmptyString(trip['id']) &&
      isNonEmptyString(trip['title']) &&
      isNonEmptyString(trip['destinationName']) &&
      isDate(trip['startDate']) &&
      isDate(trip['endDate']) &&
      members.length > 0 &&
      members.filter((member) => member.me).length <= 1 &&
      sum === 0 &&
      (!raw['open'] || raw['locked'])
    ) {
      return {
        trip: {
          id: trip['id'],
          title: trip['title'],
          destinationName: trip['destinationName'],
          startDate: trip['startDate'],
          endDate: trip['endDate'],
        },
        locked: raw['locked'],
        open: raw['open'],
        currency: 'INR',
        members,
        myNetPaise: raw['myNetPaise'],
        totalSpentPaise: raw['totalSpentPaise'],
        settlements,
        recentExpenses: raw['recentExpenses'].map(readExpense),
        settledUp: raw['settledUp'],
      };
    }
  }
  throw new Error('Unexpected ledger.');
}

function readMember(raw: unknown): Member {
  if (
    isRecord(raw) &&
    typeof raw['onTrip'] === 'boolean' &&
    typeof raw['me'] === 'boolean' &&
    isSignedPaise(raw['netPaise'])
  ) {
    const person = readPerson(raw);
    const role = oneOf(ROLES, raw['role']);
    if (role !== null) {
      return { ...person, role, onTrip: raw['onTrip'], me: raw['me'], netPaise: raw['netPaise'] };
    }
  }
  throw new Error('Unexpected member.');
}

function readSettlement(raw: unknown): Settlement {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isPaise(raw['amountPaise'], false) &&
    isInstant(raw['createdAt']) &&
    isOptionalInstant(raw['markedPaidAt']) &&
    isOptionalInstant(raw['confirmedAt']) &&
    typeof raw['canMarkPaid'] === 'boolean' &&
    typeof raw['canConfirm'] === 'boolean' &&
    typeof raw['canDispute'] === 'boolean'
  ) {
    const status = oneOf(SETTLEMENT_STATUSES, raw['status']);
    const myPart = oneOf(PARTS, raw['myPart']);
    const payment = raw['payment'] === null ? null : readPayment(raw['payment'], raw['amountPaise']);
    const from = readPerson(raw['from']);
    const to = readPerson(raw['to']);
    if (
      status !== null &&
      myPart !== null &&
      from.userId !== to.userId &&
      (!raw['canMarkPaid'] || (myPart === 'PAYER' && status === 'PROPOSED')) &&
      (!raw['canConfirm'] || (myPart === 'PAYEE' && status === 'MARKED_PAID')) &&
      (!raw['canDispute'] || myPart !== 'NONE') &&
      (payment === null || myPart === 'PAYER')
    ) {
      return {
        id: raw['id'],
        from,
        to,
        amountPaise: raw['amountPaise'],
        status,
        createdAt: raw['createdAt'],
        markedPaidAt: raw['markedPaidAt'],
        confirmedAt: raw['confirmedAt'],
        myPart,
        canMarkPaid: raw['canMarkPaid'],
        canConfirm: raw['canConfirm'],
        canDispute: raw['canDispute'],
        payment,
      };
    }
  }
  throw new Error('Unexpected settlement.');
}

function readPayment(raw: unknown, amountPaise: number): Payment {
  if (
    isRecord(raw) &&
    typeof raw['upiLink'] === 'string' &&
    typeof raw['payeeUpiId'] === 'string' &&
    typeof raw['upiChangedRecently'] === 'boolean'
  ) {
    const match = UPI_LINK_PATTERN.exec(raw['upiLink']);
    const rupees = `${String(Math.floor(amountPaise / 100))}.${String(amountPaise % 100).padStart(2, '0')}`;
    if (match !== null && match[1] === raw['payeeUpiId'] && raw['upiLink'].includes(`&am=${rupees}&`)) {
      return { upiLink: raw['upiLink'], payeeUpiId: raw['payeeUpiId'], upiChangedRecently: raw['upiChangedRecently'] };
    }
  }
  throw new Error('Unexpected payment link.');
}

function readExpense(raw: unknown): Expense {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['description']) &&
    isPaise(raw['amountPaise'], false) &&
    isInstant(raw['createdAt']) &&
    Array.isArray(raw['shares']) &&
    typeof raw['reversed'] === 'boolean' &&
    typeof raw['canReverse'] === 'boolean'
  ) {
    const shares = raw['shares'].map((share: unknown) => {
      if (isRecord(share) && isPaise(share['sharePaise'], false)) {
        return { person: readPerson(share['person']), sharePaise: share['sharePaise'] };
      }
      throw new Error('Unexpected share.');
    });
    const total = shares.reduce((sum, share) => sum + share.sharePaise, 0);
    if (total === raw['amountPaise'] && !(raw['reversed'] && raw['canReverse'])) {
      return {
        id: raw['id'],
        description: raw['description'],
        amountPaise: raw['amountPaise'],
        paidBy: readPerson(raw['paidBy']),
        createdAt: raw['createdAt'],
        shares,
        reversed: raw['reversed'],
        canReverse: raw['canReverse'],
      };
    }
  }
  throw new Error('Unexpected expense.');
}

function readHistoryPage(raw: unknown): HistoryPage {
  if (
    isRecord(raw) &&
    Array.isArray(raw['items']) &&
    Number.isInteger(raw['page']) &&
    Number.isInteger(raw['totalPages'])
  ) {
    return { items: raw['items'].map(readHistoryItem), page: raw['page'] as number, totalPages: raw['totalPages'] as number };
  }
  throw new Error('Unexpected history.');
}

function readHistoryItem(raw: unknown): HistoryItem {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['id']) &&
    isInstant(raw['createdAt']) &&
    isPaise(raw['amountPaise'], false) &&
    isSignedPaise(raw['myEffectPaise']) &&
    (raw['description'] === null || isNonEmptyString(raw['description'])) &&
    (raw['reversesId'] === null || isNonEmptyString(raw['reversesId']))
  ) {
    const kind = oneOf(LEDGER_KINDS, raw['kind']);
    const paidBy = raw['paidBy'] === null ? null : readPerson(raw['paidBy']);
    const from = raw['from'] === null ? null : readPerson(raw['from']);
    const to = raw['to'] === null ? null : readPerson(raw['to']);
    const shaped =
      (kind === 'EXPENSE' && paidBy !== null && raw['description'] !== null && from === null && to === null) ||
      (kind === 'EXPENSE_REVERSAL' && raw['reversesId'] !== null) ||
      (kind === 'SETTLEMENT' && from !== null && to !== null);
    if (kind !== null && shaped) {
      return {
        id: raw['id'],
        kind,
        createdAt: raw['createdAt'],
        description: raw['description'],
        amountPaise: raw['amountPaise'],
        paidBy,
        from,
        to,
        reversesId: raw['reversesId'],
        myEffectPaise: raw['myEffectPaise'],
      };
    }
  }
  throw new Error('Unexpected history item.');
}

function readUpiBody(raw: unknown): string | null {
  if (isRecord(raw)) {
    const upi = raw['upiId'];
    if (upi === null) {
      return null;
    }
    if (isRecord(upi) && typeof upi['upiId'] === 'string' && UPI_ID_PATTERN.test(upi['upiId'])) {
      return upi['upiId'];
    }
  }
  throw new Error('Unexpected UPI ID.');
}

function readPerson(raw: unknown): Person {
  if (isRecord(raw) && isNonEmptyString(raw['userId']) && isNonEmptyString(raw['firstName'])) {
    return { userId: raw['userId'], firstName: raw['firstName'] };
  }
  throw new Error('Unexpected person.');
}

function isPaise(value: unknown, zeroAllowed: boolean): value is number {
  return Number.isSafeInteger(value) && (value as number) >= (zeroAllowed ? 0 : 1);
}

function isSignedPaise(value: unknown): value is number {
  return Number.isSafeInteger(value);
}

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && !Number.isNaN(Date.parse(value));
}

function isOptionalInstant(value: unknown): value is string | null {
  return value === null || isInstant(value);
}

function isDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
