import { describe, expect, it } from 'vitest';
import { myReviews, trustScore, tripReviews } from '../test/trustFixtures';
import { readMyReviews, readTripReviews, readTrustScore } from './trustApi';

describe('readTrustScore', () => {
  it('accepts a breakdown whose points add up to its score', () => {
    const score = readTrustScore(trustScore());
    expect(score.pending).toBe(false);
  });

  it('accepts a pending score', () => {
    expect(readTrustScore({ pending: true, score: null, computedAt: null, breakdown: null })).toEqual({ pending: true });
  });

  it('refuses a score its breakdown does not explain', () => {
    expect(() => readTrustScore(trustScore({ score: 90 }))).toThrow();
  });

  it('refuses a component whose points are not value times weight', () => {
    const tampered = trustScore();
    (tampered.breakdown.completion as Record<string, unknown>)['points'] = 20;
    expect(() => readTrustScore(tampered)).toThrow();
  });

  it('refuses a deduction type the formula does not have, like an SOS', () => {
    const tampered = trustScore();
    tampered.breakdown.reliability.deductions[0] = {
      type: 'SOS_RAISED',
      detail: null,
      occurredAt: '2026-11-20T10:00:00Z',
      fullPoints: 20,
      nowPoints: 20,
    };
    expect(() => readTrustScore(tampered)).toThrow();
  });
});

describe('readTripReviews', () => {
  it('accepts a person waiting to be reviewed', () => {
    expect(readTripReviews(tripReviews()).people[0]?.status).toBe('TO_WRITE');
  });

  it('refuses their review while mine is still hidden: the double-blind must hold', () => {
    const mine = { rating: 5, comment: null, at: '2026-11-22T10:00:00Z' };
    const theirs = { rating: 2, comment: 'Late every day', at: '2026-11-22T10:00:00Z' };
    expect(() => readTripReviews(tripReviews({ status: 'WAITING_FOR_THEM', mine, theirs }))).toThrow();
    expect(() => readTripReviews(tripReviews({ status: 'TO_WRITE', theirs }))).toThrow();
  });

  it('accepts their review once both are revealed, or the window has closed', () => {
    const mine = { rating: 5, comment: null, at: '2026-11-22T10:00:00Z' };
    const theirs = { rating: 4, comment: 'Fine', at: '2026-11-23T10:00:00Z' };
    expect(readTripReviews(tripReviews({ status: 'REVEALED', mine, theirs })).people[0]?.theirs?.rating).toBe(4);
    expect(readTripReviews(tripReviews({ status: 'NOT_WRITTEN', theirs }, { status: 'CLOSED' })).people[0]?.theirs).not.toBeNull();
  });

  it('refuses something to write outside an open window, and a rating out of range', () => {
    expect(() => readTripReviews(tripReviews({}, { status: 'CLOSED' }))).toThrow();
    const mine = { rating: 6, comment: null, at: '2026-11-22T10:00:00Z' };
    expect(() => readTripReviews(tripReviews({ status: 'WAITING_FOR_THEM', mine }))).toThrow();
  });
});

describe('readMyReviews', () => {
  it('refuses a trip to review with nobody waiting', () => {
    expect(readMyReviews(myReviews()).toReview).toHaveLength(1);
    const empty = myReviews();
    (empty.toReview[0] as Record<string, unknown>)['waiting'] = 0;
    expect(() => readMyReviews(empty)).toThrow();
  });
});
