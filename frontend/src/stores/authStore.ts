import { create } from 'zustand';
import type { AccessGrant, AuthMethod, SessionUser, VerificationTier } from '../api/accessGrant';
import type { SessionRefresher } from '../api/authRefresh';
import { identityApi, type LoginBody, type RegistrationBody } from '../api/identityApi';
import { toApiFailure, type ApiFailure } from '../api/problem';

export type { AuthMethod, SessionUser, UserRole, VerificationTier } from '../api/accessGrant';

/**
 * - starting: the page just loaded and the one start-up refresh is in flight;
 *   nothing may decide yet whether the user is signed in.
 * - unavailable: that refresh got no answer about the session (server down,
 *   network); the app waits and tries again instead of guessing.
 * - signedIn / signedOut: known.
 */
export type AuthStatus = 'starting' | 'unavailable' | 'signedIn' | 'signedOut';

/**
 * A sign-up waiting for its emailed code. The verificationId is half of what
 * proves this browser made the registration (THREAT_MODEL.md, pre-registration
 * hijack), so it is kept like a credential: in memory, never in the URL.
 */
export interface PendingVerification {
  verificationId: string;
  /** As typed, so the user can spot a typo. Never confirmed by the server. */
  email: string;
  /** The server's wording, which never says whether the address has an account. */
  message: string;
  /** Epoch ms the current code was requested; drives the resend cooldown. */
  codeRequestedAt: number;
}

/**
 * A login whose password was right and which waits for a TOTP or backup
 * code. The challengeId finishes the login for whoever holds it, so it is
 * kept like the verificationId: in memory only. A reload drops it, and the
 * user enters the password again.
 */
export interface PendingSecondFactor {
  challengeId: string;
  /** Epoch ms, from the server's expiresIn; drives the countdown. */
  expiresAt: number;
}

/** Why the user was last signed out without asking, for the login screen to explain. */
export type SignOutReason = 'expired' | 'revokedAll';

export type ActionResult = { ok: true } | { ok: false; failure: ApiFailure };

export type LoginResult = { ok: true; next: 'signedIn' | 'secondFactor' } | { ok: false; failure: ApiFailure };

export interface AuthState {
  status: AuthStatus;
  accessToken: string | null;
  user: SessionUser | null;
  authMethods: readonly AuthMethod[];
  /** Seconds to wait before retrying the start-up refresh, when the server said. */
  startupRetryAfterSeconds: number | null;
  signOutReason: SignOutReason | null;
  pendingVerification: PendingVerification | null;
  pendingSecondFactor: PendingSecondFactor | null;
  setSession: (grant: AccessGrant) => void;
  clearSession: (reason?: SignOutReason) => void;
  restoreSession: () => Promise<void>;
  register: (body: RegistrationBody) => Promise<ActionResult>;
  verifyEmail: (code: string) => Promise<ActionResult>;
  resendVerification: () => Promise<ActionResult>;
  login: (body: LoginBody) => Promise<LoginResult>;
  completeSecondFactor: (code: string) => Promise<ActionResult>;
  abandonSecondFactor: () => void;
  logout: () => Promise<void>;
  revokeAllDevices: () => Promise<ActionResult>;
  /**
   * The tier the server just reported, e.g. after a phone code was confirmed,
   * so tier-gated screens update without waiting for the next token. Display
   * only: the server checks the tier itself on every request that needs it.
   */
  setVerificationTier: (tier: VerificationTier) => void;
}

const NO_PENDING_VERIFICATION: ApiFailure = localFailure(
  'There is no sign-up waiting for a code in this tab. Sign up again to get a new code.',
);

const NO_PENDING_SECOND_FACTOR: ApiFailure = localFailure(
  'There is no sign-in waiting for a code in this tab. Enter your email and password again.',
);

let sessionRefresher: SessionRefresher | null = null;

/** main.tsx hands over the refresher the interceptors use, so start-up shares their single flight. */
export function provideSessionRefresher(refresher: SessionRefresher): void {
  sessionRefresher = refresher;
}

/**
 * Auth state lives in this JavaScript closure only. There is deliberately no
 * persist middleware: localStorage and sessionStorage are readable by any
 * script on the page, so a single XSS bug would hand over a reusable token.
 * A reload restores the session from the httpOnly refresh cookie instead,
 * with one refresh call (restoreSession).
 */
export const useAuthStore = create<AuthState>()((set, get) => ({
  status: 'starting',
  accessToken: null,
  user: null,
  authMethods: [],
  startupRetryAfterSeconds: null,
  signOutReason: null,
  pendingVerification: null,
  pendingSecondFactor: null,

  setSession: (grant) => {
    set({
      status: 'signedIn',
      accessToken: grant.accessToken,
      user: grant.user,
      authMethods: grant.authMethods,
      startupRetryAfterSeconds: null,
      signOutReason: null,
      pendingSecondFactor: null,
    });
  },

  clearSession: (reason) => {
    set({
      status: 'signedOut',
      accessToken: null,
      user: null,
      authMethods: [],
      startupRetryAfterSeconds: null,
      signOutReason: reason ?? null,
      pendingSecondFactor: null,
    });
  },

  /**
   * The one call a page load makes to find out who is signed in. Until it
   * answers, status stays 'starting' and the app shows a loading screen, so
   * neither a logged-out page nor a redirect to login appears first.
   */
  restoreSession: async () => {
    if (sessionRefresher === null) {
      throw new Error('provideSessionRefresher must be called before restoreSession.');
    }
    set({ status: 'starting', startupRetryAfterSeconds: null });
    const outcome = await sessionRefresher.refresh();
    if (outcome.kind === 'unavailable') {
      set({ status: 'unavailable', startupRetryAfterSeconds: toApiFailure(outcome.error).retryAfterSeconds });
    }
  },

  register: async (body) => {
    try {
      const challenge = await identityApi.register(body);
      set({
        pendingVerification: {
          verificationId: challenge.verificationId,
          email: body.email,
          message: challenge.message,
          codeRequestedAt: Date.now(),
        },
      });
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },

  verifyEmail: async (code) => {
    const pending = get().pendingVerification;
    if (pending === null) {
      return { ok: false, failure: NO_PENDING_VERIFICATION };
    }
    try {
      await identityApi.verifyEmail(pending.verificationId, code);
      set({ pendingVerification: null });
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },

  /**
   * The server supersedes the old code and answers with the ID to use from
   * now on; during its cooldown that is the same ID again. Either way the
   * returned ID replaces the stored one, so the next verify always pairs the
   * newest code with the newest ID.
   */
  resendVerification: async () => {
    const pending = get().pendingVerification;
    if (pending === null) {
      return { ok: false, failure: NO_PENDING_VERIFICATION };
    }
    try {
      const challenge = await identityApi.resendVerification(pending.verificationId);
      set({
        pendingVerification: {
          ...pending,
          verificationId: challenge.verificationId,
          message: challenge.message,
          codeRequestedAt: Date.now(),
        },
      });
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },

  login: async (body) => {
    set({ pendingSecondFactor: null });
    try {
      const outcome = await identityApi.login(body);
      if (outcome.kind === 'secondFactor') {
        set({
          pendingSecondFactor: {
            challengeId: outcome.challengeId,
            expiresAt: Date.now() + outcome.expiresInSeconds * 1000,
          },
        });
        return { ok: true, next: 'secondFactor' };
      }
      get().setSession(outcome.grant);
      return { ok: true, next: 'signedIn' };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },

  /**
   * The challenge stays in the store after any failure: after a wrong code
   * the user may try again within the server's limit, and after an expired
   * one the screen explains it and the user leaves through abandonSecondFactor.
   */
  completeSecondFactor: async (code) => {
    const pending = get().pendingSecondFactor;
    if (pending === null) {
      return { ok: false, failure: NO_PENDING_SECOND_FACTOR };
    }
    try {
      const grant = await identityApi.completeSecondFactor(pending.challengeId, code);
      get().setSession(grant);
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },

  abandonSecondFactor: () => {
    set({ pendingSecondFactor: null });
  },

  /**
   * The tab signs out whatever the server answers: the user asked to leave,
   * and keeping the token after a failed call would be the surprising
   * outcome. The server-side session still ends when the call succeeds,
   * which it does whenever the server is reachable.
   */
  logout: async () => {
    try {
      await identityApi.logout();
    } catch {
      // Signed out locally regardless; see above.
    }
    get().clearSession();
  },

  /** Ends every session, this tab's included, so this tab signs out too. */
  revokeAllDevices: async () => {
    try {
      await identityApi.revokeAllDevices();
      get().clearSession('revokedAll');
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },

  setVerificationTier: (tier) => {
    const user = get().user;
    if (user !== null && user.verificationTier !== tier) {
      set({ user: { ...user, verificationTier: tier } });
    }
  },
}));

function localFailure(message: string): ApiFailure {
  return {
    kind: 'unexpected',
    status: null,
    code: null,
    message,
    fieldErrors: {},
    formErrors: [],
    correlationId: null,
    retryAfterSeconds: null,
  };
}
