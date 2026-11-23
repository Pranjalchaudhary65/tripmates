import { describe, expect, it } from 'vitest';
import { readReturnPath } from './returnPath';

describe('readReturnPath', () => {
  it.each([
    ['/account/sessions', '/account/sessions'],
    ['/admin/users?tab=open#top', '/admin/users?tab=open#top'],
  ])('accepts the in-app path %s', (from, expected) => {
    expect(readReturnPath({ from })).toBe(expected);
  });

  it.each([
    'https://evil.example/',
    '//evil.example/',
    '/\\evil.example/',
    'javascript:alert(1)',
    'account/sessions',
    '/login',
    '/login/second-factor',
    '/signup?x=1',
  ])('refuses %s', (from) => {
    expect(readReturnPath({ from })).toBeNull();
  });

  it.each([null, undefined, 'string', { from: 42 }, {}])('refuses state %j', (state) => {
    expect(readReturnPath(state)).toBeNull();
  });
});
