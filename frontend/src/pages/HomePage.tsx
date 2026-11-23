import { Link } from 'react-router';
import { useAuthStore, type VerificationTier } from '../stores/authStore';

const TIER_LABELS: Record<VerificationTier, string> = {
  NONE: 'Not verified',
  EMAIL: 'Email verified',
  PHONE: 'Phone verified',
  SELFIE_MATCH: 'Photo ID verified',
};

export function HomePage() {
  const user = useAuthStore((state) => state.user);

  return (
    <section>
      <h1 className="text-3xl font-bold text-teal-800">Travel with strangers you can trust</h1>
      <p className="mt-3 max-w-prose text-slate-600">
        Verified people, commitments that count, and a safety net for the whole trip.
      </p>

      {user === null ? (
        <div className="mt-6 flex gap-4">
          <Link to="/signup" className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800">
            Create an account
          </Link>
          <Link to="/login" className="px-4 py-2 font-medium text-teal-700 underline">
            Log in
          </Link>
        </div>
      ) : (
        <div role="status" className="mt-6 max-w-prose rounded-md border border-teal-200 bg-teal-50 px-4 py-3">
          <p className="font-medium text-teal-900">You are signed in.</p>
          <p className="mt-1 text-sm text-teal-900">Verification level: {TIER_LABELS[user.verificationTier]}</p>
          <p className="mt-2 text-sm">
            <Link to="/account/security" className="font-medium text-teal-800 underline">
              Two-factor authentication
            </Link>
            {' · '}
            <Link to="/account/sessions" className="font-medium text-teal-800 underline">
              Signed-in devices
            </Link>
          </p>
        </div>
      )}
    </section>
  );
}
