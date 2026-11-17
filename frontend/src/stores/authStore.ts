import { create } from 'zustand';
import { identityApi, type LoginBody, type RegistrationBody } from '../api/identityApi';
import { toApiFailure, type ApiFailure } from '../api/problem';

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
 * The login endpoint accepted the email and password. It issues no token,
 * so this is not a session: it grants nothing on any other request, and
 * route guards look at accessToken and user, never at this.
 */
export interface ConfirmedCredentials {
  userId: string;
  verificationTier: VerificationTier;
}

export type ActionResult = { ok: true } | { ok: false; failure: ApiFailure };

export interface AuthState {
  accessToken: string | null;
  user: SessionUser | null;
  confirmedCredentials: ConfirmedCredentials | null;
  pendingVerification: PendingVerification | null;
  setAccessToken: (accessToken: string) => void;
  setSession: (accessToken: string, user: SessionUser) => void;
  clearSession: () => void;
  register: (body: RegistrationBody) => Promise<ActionResult>;
  verifyEmail: (code: string) => Promise<ActionResult>;
  resendVerification: () => Promise<ActionResult>;
  login: (body: LoginBody) => Promise<ActionResult>;
}

const NO_PENDING_VERIFICATION: ApiFailure = {
  kind: 'unexpected',
  status: null,
  code: null,
  message: 'There is no sign-up waiting for a code in this tab. Sign up again to get a new code.',
  fieldErrors: {},
  formErrors: [],
  correlationId: null,
  retryAfterSeconds: null,
};

/**
 * Auth state lives in this JavaScript closure only. There is deliberately no
 * persist middleware: localStorage and sessionStorage are readable by any
 * script on the page, so a single XSS bug would hand over a reusable token.
 *
 * A page reload therefore starts empty. Once the refresh endpoint exists,
 * the httpOnly refresh cookie restores the session through the silent
 * refresh interceptor. Until then nothing is restored and nothing is
 * requested on load: the app never calls an endpoint the backend does not
 * have.
 */
export const useAuthStore = create<AuthState>()((set, get) => ({
  accessToken: null,
  user: null,
  confirmedCredentials: null,
  pendingVerification: null,

  setAccessToken: (accessToken) => {
    set({ accessToken });
  },
  setSession: (accessToken, user) => {
    set({ accessToken, user });
  },
  clearSession: () => {
    set({ accessToken: null, user: null, confirmedCredentials: null });
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
    set({ confirmedCredentials: null });
    try {
      const result = await identityApi.login(body);
      set({ confirmedCredentials: { userId: result.userId, verificationTier: result.verificationTier } });
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: toApiFailure(error) };
    }
  },
}));
