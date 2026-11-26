import type { DataRequestStatus, DataRequestType, ReportStatus } from '../api/adminApi';

/** Words for the codes the admin routes send. The server only ever sends codes. */

export const REPORT_STATUS_LABELS: Record<ReportStatus, string> = {
  OPEN: 'Open',
  ACTIONED: 'Actioned',
  DISMISSED: 'Dismissed',
};

const AUDIT_EVENT_LABELS: Record<string, string> = {
  ADMIN_VERIFICATION_DECIDED: 'Selfie check decided',
  ACCOUNT_SUSPENDED: 'Account suspended',
  ACCOUNT_REINSTATED: 'Account reinstated',
  REPORT_FILED: 'Report filed',
  REPORT_CLOSED: 'Report closed',
  SOS_RAISED: 'SOS raised',
  SETTLEMENT_CONFIRMED: 'Payment confirmed',
  CONSENT_CHANGED: 'Consent changed',
  DATA_EXPORT_REQUESTED: 'Data export requested',
  DATA_EXPORT_COMPLETED: 'Data export ready',
  DATA_EXPORT_DOWNLOADED: 'Data export downloaded',
  ACCOUNT_DELETION_REQUESTED: 'Account deletion requested',
  ACCOUNT_DELETED: 'Account deleted',
  AUDIT_CHECK_RUN: 'Integrity check run by hand',
};

/** A known event in words; an event this screen predates is shown as its code rather than hidden. */
export function auditEventLabel(eventType: string): string {
  return AUDIT_EVENT_LABELS[eventType] ?? eventType;
}

export const DATA_REQUEST_TYPE_LABELS: Record<DataRequestType, string> = {
  EXPORT: 'Data export',
  DELETION: 'Account deletion',
};

export const DATA_REQUEST_STATUS_LABELS: Record<DataRequestStatus, string> = {
  PENDING: 'Waiting to run',
  COMPLETED: 'Done',
  FAILED: 'Failed',
};

const WHEN = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' });

export function formatWhen(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'at an unknown time' : WHEN.format(at);
}

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
