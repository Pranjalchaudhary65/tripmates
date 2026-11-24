import { describe, expect, it } from 'vitest';
import { expiresWithin } from '../chat/chatConnection';
import { codePointLength, validateMessage } from './chatRules';

describe('message rules', () => {
  it('needs something other than whitespace', () => {
    expect(validateMessage('   \n ')).toBe('Write something before sending.');
    expect(validateMessage('hi')).toBeNull();
  });

  it('counts characters as the server does, so an emoji is one', () => {
    expect(codePointLength('🙂🙂')).toBe(2);
    expect(validateMessage('🙂'.repeat(2000))).toBeNull();
    expect(validateMessage('a'.repeat(2001))).toBe('Keep a message under 2000 characters. Split a longer one in two.');
  });
});

describe('access token expiry hint', () => {
  const token = (payload: unknown) => `e30.${btoa(JSON.stringify(payload)).replace(/=+$/, '')}.sig`;

  it('says a token is expiring once it is within the margin', () => {
    const now = 1_000_000_000_000;
    expect(expiresWithin(token({ exp: now / 1000 + 30 }), 60_000, now)).toBe(true);
    expect(expiresWithin(token({ exp: now / 1000 + 600 }), 60_000, now)).toBe(false);
  });

  it('leaves the decision to the server for a token it cannot read', () => {
    expect(expiresWithin('not-a-jwt', 60_000)).toBe(false);
    expect(expiresWithin(token({ sub: 'x' }), 60_000)).toBe(false);
    expect(expiresWithin('a.%%%.c', 60_000)).toBe(false);
  });
});
