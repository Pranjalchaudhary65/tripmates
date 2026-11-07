// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from './authStore';

const USER = { id: 'u-1', role: 'USER', verificationTier: 'PHONE' } as const;

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    useAuthStore.getState().clearSession();
  });

  it('starts with no session', () => {
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('holds the session and clears it completely', () => {
    useAuthStore.getState().setSession('token-1', USER);
    expect(useAuthStore.getState().accessToken).toBe('token-1');
    expect(useAuthStore.getState().user).toEqual(USER);

    useAuthStore.getState().setAccessToken('token-2');
    expect(useAuthStore.getState().accessToken).toBe('token-2');
    expect(useAuthStore.getState().user).toEqual(USER);

    useAuthStore.getState().clearSession();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('never writes the token to browser storage', () => {
    useAuthStore.getState().setSession('secret-token', USER);

    expect(localStorage).toHaveLength(0);
    expect(sessionStorage).toHaveLength(0);
    expect(document.cookie).not.toContain('secret-token');
  });
});
