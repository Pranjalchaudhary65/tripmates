/** Admin route bodies the way the 12a controllers write them. */

export const REPORTED_ID = '7b1e2c3d-4a5b-4c6d-8e7f-9a0b1c2d3e4f';
export const REPORTER_ID = '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
export const TRIP_ID = '9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a';
export const HASH = 'a'.repeat(64);

export function report(overrides: Record<string, unknown> = {}, reported: Record<string, unknown> = {}) {
  return {
    id: 'r-1',
    reason: 'HARASSMENT',
    status: 'OPEN',
    createdAt: '2026-11-20T09:00:00Z',
    tripId: TRIP_ID,
    details: 'Kept messaging after I said stop.',
    detailsUnreadable: false,
    reporter: { userId: REPORTER_ID, firstName: 'Asha', status: null, reporters: 0 },
    reported: { userId: REPORTED_ID, firstName: 'Ravi', status: 'ACTIVE', reporters: 2, ...reported },
    ...overrides,
  };
}

export function page<T>(items: T[], pageNumber = 0, totalPages = 1) {
  return { items, page: pageNumber, size: 20, totalItems: items.length, totalPages };
}

export function auditEntry(sequenceNo: number, overrides: Record<string, unknown> = {}) {
  return {
    sequenceNo,
    actorUserId: null,
    eventType: 'ACCOUNT_SUSPENDED',
    entityType: 'USER',
    entityId: REPORTED_ID,
    metadata: '{"by": "REPORT_THRESHOLD", "vouchersPenalised": 1}',
    createdAt: '2026-11-21T10:00:00Z',
    rowHash: HASH,
    ...overrides,
  };
}

export function check(overrides: Record<string, unknown> = {}) {
  return { checkedAt: '2026-11-26T02:00:00Z', rowsChecked: 42, intact: true, firstBrokenSeq: null, ...overrides };
}

export function auditPage(entries: ReturnType<typeof auditEntry>[], latestCheck: unknown = check()) {
  return { entries: page(entries), latestCheck };
}

export function dataRequest(overrides: Record<string, unknown> = {}) {
  return {
    id: 'd-1',
    type: 'EXPORT',
    status: 'COMPLETED',
    requestedAt: '2026-11-25T09:00:00Z',
    completedAt: '2026-11-25T09:01:00Z',
    expiresAt: '2026-12-02T09:01:00Z',
    downloadable: true,
    ...overrides,
  };
}

/** AdminUserResponse. */
export function adminUser(overrides: Record<string, unknown> = {}) {
  return {
    id: REPORTED_ID,
    email: 'ravi@example.test',
    fullName: 'Ravi Kumar',
    role: 'USER',
    accountStatus: 'ACTIVE',
    verificationTier: 'PHONE',
    twoFactorEnabled: false,
    emailVerifiedAt: '2026-10-01T09:00:00Z',
    createdAt: '2026-10-01T08:00:00Z',
    deletedAt: null,
    ...overrides,
  };
}
