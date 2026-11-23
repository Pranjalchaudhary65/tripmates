import { Link, Navigate, Outlet, useLocation } from 'react-router';
import { useAuthStore, type VerificationTier } from '../stores/authStore';
import { readReturnPath, type ReturnState } from './returnPath';
import { tierAtLeast, TIER_REQUIREMENT } from './tiers';

/**
 * These guards decide what to render, never what is allowed: every API
 * call is authorized by the server again, so a guard tampered with in
 * DevTools reveals an empty screen and nothing else. They only run once the
 * start-up refresh has answered (StartupGate), so they never act on a
 * session that has not been looked up yet.
 */

/** Signed-in users only; anyone else goes to login and comes back here afterwards. */
export function RequireSession() {
  const status = useAuthStore((state) => state.status);
  const location = useLocation();
  if (status !== 'signedIn') {
    const state: ReturnState = { from: `${location.pathname}${location.search}${location.hash}` };
    return <Navigate to="/login" replace state={state} />;
  }
  return <Outlet />;
}

/**
 * Admin screens need the ADMIN role and a session that passed a second
 * factor, the same two things the server requires on /admin. A user who
 * fails either gets an explanation, not a redirect: they are signed in, and
 * login would not change the answer for a non-admin.
 */
export function RequireAdmin() {
  const user = useAuthStore((state) => state.user);
  const authMethods = useAuthStore((state) => state.authMethods);
  if (user?.role !== 'ADMIN') {
    return <AccessDenied message="This area is for TripMates moderators." />;
  }
  if (!authMethods.includes('otp')) {
    return (
      <AccessDenied message="Admin tools need a sign-in with your authenticator app. Log out, then log in again with your code." />
    );
  }
  return <Outlet />;
}

/**
 * Screens that only make sense from a verification tier up, such as
 * vouching for someone, which the server refuses below SELFIE_MATCH. Below
 * the tier the user is told what is missing and where to get it; nothing
 * else in the app is gated on verification.
 */
export function RequireTier({ minimum }: { minimum: VerificationTier }) {
  const tier = useAuthStore((state) => state.user?.verificationTier ?? 'NONE');
  if (!tierAtLeast(tier, minimum)) {
    return (
      <section>
        <h1 className="text-2xl font-bold">Not available yet</h1>
        <p className="mt-2 text-slate-600">This needs {TIER_REQUIREMENT[minimum]}. It takes a minute or two.</p>
        <Link to="/account/verification" className="mt-4 inline-block font-medium text-teal-700 underline">
          Go to the verification centre
        </Link>
      </section>
    );
  }
  return <Outlet />;
}

/**
 * Login and sign-up screens are for signed-out users. Once a session
 * exists (a login or second factor just succeeded, or a signed-in user
 * opened /login) this sends the user where they were headed, or home.
 */
export function GuestOnly() {
  const status = useAuthStore((state) => state.status);
  const location = useLocation();
  if (status === 'signedIn') {
    return <Navigate to={readReturnPath(location.state) ?? '/'} replace />;
  }
  return <Outlet />;
}

function AccessDenied({ message }: { message: string }) {
  return (
    <section>
      <h1 className="text-2xl font-bold">Not available</h1>
      <p className="mt-2 text-slate-600">{message}</p>
      <Link to="/" className="mt-4 inline-block font-medium text-teal-700 underline">
        Back to home
      </Link>
    </section>
  );
}
