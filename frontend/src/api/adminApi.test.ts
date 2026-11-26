import { describe, expect, it } from 'vitest';
import { auditEntry, auditPage, check, dataRequest, report } from '../test/adminFixtures';
import { readAuditPage, readCheck, readDataRequest, readReport } from './adminApi';

describe('readReport', () => {
  it('reads a report with the reported person active', () => {
    const read = readReport(report());
    expect(read.reported).toEqual({
      userId: '7b1e2c3d-4a5b-4c6d-8e7f-9a0b1c2d3e4f',
      firstName: 'Ravi',
      active: true,
      reporters: 2,
    });
    expect(read.details).toBe('Kept messaging after I said stop.');
  });

  it('refuses a reason or a state the server does not have', () => {
    expect(() => readReport(report({ reason: 'SAFETY_CANCELLATION' }))).toThrow();
    expect(() => readReport(report({ status: 'PENDING' }))).toThrow();
    expect(() => readReport(report({}, { status: 'SUSPENDED' }))).toThrow();
  });

  it('refuses details that are both present and unreadable', () => {
    expect(() => readReport(report({ detailsUnreadable: true }))).toThrow();
    expect(readReport(report({ details: null, detailsUnreadable: true })).detailsUnreadable).toBe(true);
  });
});

describe('readAuditPage', () => {
  it('reads metadata into plain values', () => {
    const read = readAuditPage(auditPage([auditEntry(3)]));
    expect(read.entries.items[0]?.metadata).toEqual({ by: 'REPORT_THRESHOLD', vouchersPenalised: 1 });
    expect(read.latestCheck?.intact).toBe(true);
  });

  it('refuses metadata that is not a flat object of IDs, codes and numbers', () => {
    expect(() => readAuditPage(auditPage([auditEntry(1, { metadata: '{"who": {"name": "Ravi"}}' })]))).toThrow();
    expect(() => readAuditPage(auditPage([auditEntry(1, { metadata: '["x"]' })]))).toThrow();
    expect(() => readAuditPage(auditPage([auditEntry(1, { metadata: 'not json' })]))).toThrow();
  });

  it('refuses a row hash that is not a SHA-256 in hex, and a sequence below 1', () => {
    expect(() => readAuditPage(auditPage([auditEntry(1, { rowHash: 'abc' })]))).toThrow();
    expect(() => readAuditPage(auditPage([auditEntry(0)]))).toThrow();
  });

  it('accepts a log that has never been checked', () => {
    expect(readAuditPage(auditPage([], null)).latestCheck).toBeNull();
  });
});

describe('readCheck', () => {
  it('names the first broken row exactly when the chain is not intact', () => {
    expect(readCheck(check({ intact: false, firstBrokenSeq: 7 })).firstBrokenSeq).toBe(7);
    expect(() => readCheck(check({ intact: false, firstBrokenSeq: null }))).toThrow();
    expect(() => readCheck(check({ intact: true, firstBrokenSeq: 7 }))).toThrow();
  });
});

describe('readDataRequest', () => {
  it('reads status only and refuses a type it does not know', () => {
    expect(readDataRequest(dataRequest()).downloadable).toBe(true);
    expect(() => readDataRequest(dataRequest({ type: 'RECTIFICATION' }))).toThrow();
  });
});
