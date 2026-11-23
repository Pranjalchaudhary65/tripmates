import { describe, expect, it } from 'vitest';
import { accessGrant, fakeAccessToken } from '../test/apiServer';
import { readAccessGrant, readAuthMethods } from './accessGrant';

describe('readAuthMethods', () => {
  it('reads amr from the token payload', () => {
    expect(readAuthMethods(fakeAccessToken(['pwd']))).toEqual(['pwd']);
    expect(readAuthMethods(fakeAccessToken(['pwd', 'otp']))).toEqual(['pwd', 'otp']);
  });

  it('keeps only the methods the backend issues', () => {
    expect(readAuthMethods(fakeAccessToken(['pwd', 'otp', 'hwk']))).toEqual(['pwd', 'otp']);
  });

  it.each(['', 'not-a-jwt', 'a.%%%.c', `a.${btoa('"just a string"')}.c`])('reads nothing from %s', (token) => {
    expect(readAuthMethods(token)).toEqual([]);
  });
});

describe('readAccessGrant', () => {
  it('reads the token, the user and the amr', () => {
    const grant = readAccessGrant(accessGrant('u-1', 'PHONE', { role: 'ADMIN', amr: ['pwd', 'otp'] }));

    expect(grant.user).toEqual({ id: 'u-1', role: 'ADMIN', verificationTier: 'PHONE' });
    expect(grant.authMethods).toEqual(['pwd', 'otp']);
  });

  it.each([
    { accessToken: '', user: { id: 'u-1', role: 'USER', verificationTier: 'EMAIL' } },
    { accessToken: 'x', user: { id: 'u-1', role: 'OWNER', verificationTier: 'EMAIL' } },
    { accessToken: 'x', user: { id: 'u-1', role: 'USER', verificationTier: 'PASSPORT' } },
    { accessToken: 'x' },
    null,
  ])('refuses %j', (body) => {
    expect(() => readAccessGrant(body)).toThrow('Unexpected access grant.');
  });
});
