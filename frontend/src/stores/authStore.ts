import { create } from 'zustand';

/** Mirrors users.id_verification_tier in DESIGN.md. */
export type VerificationTier = 'NONE' | 'EMAIL' | 'PHONE' | 'SELFIE_MATCH';

/** Mirrors users.role in DESIGN.md. */
export type UserRole = 'USER' | 'ADMIN';

/** What route guards need to know about the signed-in user. */
export interface SessionUser {
  id: string;
  role: UserRole;
  verificationTier: VerificationTier;
}

export interface AuthState {
  accessToken: string | null;
  user: SessionUser | null;
  setAccessToken: (accessToken: string) => void;
  setSession: (accessToken: string, user: SessionUser) => void;
  clearSession: () => void;
}

/**
 * Auth state lives in this JavaScript closure only. There is deliberately no
 * persist middleware: localStorage and sessionStorage are readable by any
 * script on the page, so a single XSS bug would hand over a reusable token.
 * A page reload starts with no access token, and the refresh-token cookie
 * (httpOnly, so invisible to JavaScript) restores the session through the
 * refresh endpoint.
 */
export const useAuthStore = create<AuthState>()((set) => ({
  accessToken: null,
  user: null,
  setAccessToken: (accessToken) => {
    set({ accessToken });
  },
  setSession: (accessToken, user) => {
    set({ accessToken, user });
  },
  clearSession: () => {
    set({ accessToken: null, user: null });
  },
}));
