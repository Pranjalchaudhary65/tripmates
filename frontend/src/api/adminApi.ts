import type { AxiosInstance } from 'axios';
import { isNonEmptyString, isRecord } from './accessGrant';
import { apiClient } from './client';
import { REPORT_REASONS, type ReportReason } from './trustApi';
import type { Page } from './verificationApi';

export type ReportStatus = 'OPEN' | 'ACTIONED' | 'DISMISSED';
export type ReportOutcome = Exclude<ReportStatus, 'OPEN'>;
export type DataRequestType = 'EXPORT' | 'DELETION';
export type DataRequestStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export const REPORT_STATUSES: readonly ReportStatus[] = ['OPEN', 'ACTIONED', 'DISMISSED'];
const DATA_REQUEST_TYPES: readonly DataRequestType[] = ['EXPORT', 'DELETION'];
const DATA_REQUEST_STATUSES: readonly DataRequestStatus[] = ['PENDING', 'COMPLETED', 'FAILED'];

/** One side of a report. status and reporters describe the reported person only. */
export interface ReportParty {
  userId: string;
  firstName: string;
}

export interface ReportedParty extends ReportParty {
  /** NOT_ACTIVE covers suspended, banned and deleted accounts. */
  active: boolean;
  /** Different people with an undismissed report about them, this one included. */
  reporters: number;
}

/** AdminModerationService.ReportItem. */
export interface AdminReport {
  id: string;
  reason: ReportReason;
  status: ReportStatus;
  createdAt: string;
  tripId: string | null;
  /** The reporter's own words, opened for the moderator; null when none were given or they can't be read. */
  details: string | null;
  /** Details were written but the reporter's key has since been shredded. */
  detailsUnreadable: boolean;
  reporter: ReportParty;
  reported: ReportedParty;
}

/** A metadata value the audit log allows: IDs, codes and numbers, never free text about a person. */
export type AuditValue = string | number | boolean;

/** AdminComplianceController.AuditItem. */
export interface AuditEntry {
  sequenceNo: number;
  actorUserId: string | null;
  eventType: string;
  entityType: string;
  entityId: string;
  metadata: Record<string, AuditValue>;
  createdAt: string;
  rowHash: string;
}

/** AdminComplianceController.Check. firstBrokenSeq is set exactly when intact is false. */
export interface IntegrityCheck {
  checkedAt: string;
  rowsChecked: number;
  intact: boolean;
  firstBrokenSeq: number | null;
}

export interface AuditPage {
  entries: Page<AuditEntry>;
  latestCheck: IntegrityCheck | null;
}

/** DataRequestController.Item: status only, never an export's contents. */
export interface AdminDataRequest {
  id: string;
  type: DataRequestType;
  status: DataRequestStatus;
  requestedAt: string;
  completedAt: string | null;
  expiresAt: string | null;
  downloadable: boolean;
}

const PAGE_SIZE = 20;

export function createAdminApi(client: AxiosInstance) {
  return {
    async reports(status: ReportStatus, page: number): Promise<Page<AdminReport>> {
      const response = await client.get<unknown>('/admin/reports', { params: { status, page, size: PAGE_SIZE } });
      return readPage(response.data, readReport);
    },

    async decideReport(reportId: string, decision: ReportOutcome, suspend: boolean): Promise<void> {
      await client.post(`/admin/reports/${encodeURIComponent(reportId)}/decision`, { decision, suspend });
    },

    async suspend(userId: string): Promise<void> {
      await client.post(`/admin/users/${encodeURIComponent(userId)}/suspend`);
    },

    async reinstate(userId: string): Promise<void> {
      await client.post(`/admin/users/${encodeURIComponent(userId)}/reinstate`);
    },

    async auditLog(entityId: string | null, page: number): Promise<AuditPage> {
      const params: Record<string, string | number> = { page, size: PAGE_SIZE };
      if (entityId !== null) {
        params['entityId'] = entityId;
      }
      const response = await client.get<unknown>('/admin/audit', { params });
      return readAuditPage(response.data);
    },

    async checkAuditChain(): Promise<IntegrityCheck> {
      const response = await client.post<unknown>('/admin/audit/integrity-check');
      return readCheck(response.data);
    },

    async dataRequests(page: number): Promise<Page<AdminDataRequest>> {
      const response = await client.get<unknown>('/admin/data-requests', { params: { page, size: PAGE_SIZE } });
      return readPage(response.data, readDataRequest);
    },
  };
}

export type AdminApi = ReturnType<typeof createAdminApi>;

export const adminApi: AdminApi = createAdminApi(apiClient);

function readParty(raw: unknown): ReportParty {
  if (isRecord(raw) && isNonEmptyString(raw['userId']) && typeof raw['firstName'] === 'string') {
    return { userId: raw['userId'], firstName: raw['firstName'] };
  }
  throw new Error('Unexpected report party.');
}

export function readReport(raw: unknown): AdminReport {
  if (!isRecord(raw)) {
    throw new Error('Unexpected report.');
  }
  const reason = oneOf(REPORT_REASONS, raw['reason']);
  const status = oneOf(REPORT_STATUSES, raw['status']);
  const reported = raw['reported'];
  const details = raw['details'];
  const unreadable = raw['detailsUnreadable'];
  if (
    reason === null ||
    status === null ||
    !isNonEmptyString(raw['id']) ||
    !isNonEmptyString(raw['createdAt']) ||
    !(raw['tripId'] === null || isNonEmptyString(raw['tripId'])) ||
    !(details === null || typeof details === 'string') ||
    typeof unreadable !== 'boolean' ||
    (unreadable && details !== null) ||
    !isRecord(reported) ||
    (reported['status'] !== 'ACTIVE' && reported['status'] !== 'NOT_ACTIVE') ||
    !isCount(reported['reporters'])
  ) {
    throw new Error('Unexpected report.');
  }
  return {
    id: raw['id'],
    reason,
    status,
    createdAt: raw['createdAt'],
    tripId: raw['tripId'],
    details,
    detailsUnreadable: unreadable,
    reporter: readParty(raw['reporter']),
    reported: { ...readParty(reported), active: reported['status'] === 'ACTIVE', reporters: reported['reporters'] },
  };
}

/**
 * The server sends metadata as a JSON object string. Anything but a flat
 * object of strings, numbers and booleans is refused, the same rule AuditLog
 * applies when it writes a row.
 */
function readMetadata(raw: unknown): Record<string, AuditValue> {
  if (typeof raw !== 'string') {
    throw new Error('Unexpected audit metadata.');
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error('Unexpected audit metadata.');
  }
  if (!isRecord(parsed) || Array.isArray(parsed)) {
    throw new Error('Unexpected audit metadata.');
  }
  const metadata: Record<string, AuditValue> = {};
  for (const [key, value] of Object.entries(parsed)) {
    if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') {
      throw new Error('Unexpected audit metadata.');
    }
    metadata[key] = value;
  }
  return metadata;
}

function readAuditEntry(raw: unknown): AuditEntry {
  if (
    isRecord(raw) &&
    typeof raw['sequenceNo'] === 'number' &&
    Number.isInteger(raw['sequenceNo']) &&
    raw['sequenceNo'] >= 1 &&
    (raw['actorUserId'] === null || isNonEmptyString(raw['actorUserId'])) &&
    typeof raw['eventType'] === 'string' &&
    /^[A-Z][A-Z_]{0,63}$/.test(raw['eventType']) &&
    typeof raw['entityType'] === 'string' &&
    /^[A-Z][A-Z_]{0,63}$/.test(raw['entityType']) &&
    isNonEmptyString(raw['entityId']) &&
    isNonEmptyString(raw['createdAt']) &&
    typeof raw['rowHash'] === 'string' &&
    /^[0-9a-f]{64}$/.test(raw['rowHash'])
  ) {
    return {
      sequenceNo: raw['sequenceNo'],
      actorUserId: raw['actorUserId'],
      eventType: raw['eventType'],
      entityType: raw['entityType'],
      entityId: raw['entityId'],
      metadata: readMetadata(raw['metadata']),
      createdAt: raw['createdAt'],
      rowHash: raw['rowHash'],
    };
  }
  throw new Error('Unexpected audit entry.');
}

export function readCheck(raw: unknown): IntegrityCheck {
  if (
    isRecord(raw) &&
    isNonEmptyString(raw['checkedAt']) &&
    isCount(raw['rowsChecked']) &&
    typeof raw['intact'] === 'boolean' &&
    (raw['intact'] ? raw['firstBrokenSeq'] === null : isCount(raw['firstBrokenSeq']) && raw['firstBrokenSeq'] >= 1)
  ) {
    return {
      checkedAt: raw['checkedAt'],
      rowsChecked: raw['rowsChecked'],
      intact: raw['intact'],
      firstBrokenSeq: raw['intact'] ? null : (raw['firstBrokenSeq'] as number),
    };
  }
  throw new Error('Unexpected integrity check.');
}

export function readAuditPage(body: unknown): AuditPage {
  if (isRecord(body) && 'latestCheck' in body) {
    return {
      entries: readPage(body['entries'], readAuditEntry),
      latestCheck: body['latestCheck'] === null ? null : readCheck(body['latestCheck']),
    };
  }
  throw new Error('Unexpected audit page.');
}

export function readDataRequest(raw: unknown): AdminDataRequest {
  const type = isRecord(raw) ? oneOf(DATA_REQUEST_TYPES, raw['type']) : null;
  const status = isRecord(raw) ? oneOf(DATA_REQUEST_STATUSES, raw['status']) : null;
  if (
    isRecord(raw) &&
    type !== null &&
    status !== null &&
    isNonEmptyString(raw['id']) &&
    isNonEmptyString(raw['requestedAt']) &&
    (raw['completedAt'] === null || isNonEmptyString(raw['completedAt'])) &&
    (raw['expiresAt'] === null || isNonEmptyString(raw['expiresAt'])) &&
    typeof raw['downloadable'] === 'boolean'
  ) {
    return {
      id: raw['id'],
      type,
      status,
      requestedAt: raw['requestedAt'],
      completedAt: raw['completedAt'],
      expiresAt: raw['expiresAt'],
      downloadable: raw['downloadable'],
    };
  }
  throw new Error('Unexpected data request.');
}

function readPage<T>(body: unknown, readItem: (raw: unknown) => T): Page<T> {
  if (
    isRecord(body) &&
    Array.isArray(body['items']) &&
    typeof body['page'] === 'number' &&
    typeof body['size'] === 'number' &&
    typeof body['totalItems'] === 'number' &&
    typeof body['totalPages'] === 'number'
  ) {
    return {
      items: body['items'].map(readItem),
      page: body['page'],
      size: body['size'],
      totalItems: body['totalItems'],
      totalPages: body['totalPages'],
    };
  }
  throw new Error('Unexpected page.');
}

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

function oneOf<T extends string>(allowed: readonly T[], value: unknown): T | null {
  return allowed.find((candidate) => candidate === value) ?? null;
}
