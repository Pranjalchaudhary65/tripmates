import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { StartupGate } from '../auth/StartupGate';
import { ChatConnectionManager } from '../components/ChatConnectionManager';
import { useAuthStore } from '../stores/authStore';
import { selectUnreadTotal, useChatStore } from '../stores/chatStore';

const navLinkClassName = ({ isActive }: { isActive: boolean }) =>
  `rounded px-2 py-1 text-sm font-medium hover:bg-teal-600 ${isActive ? 'bg-teal-800' : ''}`;

export function AppLayout() {
  return (
    <StartupGate>
      <ChatConnectionManager />
      <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
        <header className="bg-teal-700 text-white shadow">
          <nav className="mx-auto flex max-w-5xl items-center gap-2 px-4 py-3">
            <Link to="/" className="mr-auto text-lg font-semibold tracking-tight">
              TripMates
            </Link>
            <SessionNav />
          </nav>
        </header>
        <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
          <Outlet />
        </main>
      </div>
    </StartupGate>
  );
}

function SessionNav() {
  const status = useAuthStore((state) => state.status);
  const isAdmin = useAuthStore((state) => state.user?.role === 'ADMIN');
  const logout = useAuthStore((state) => state.logout);
  const unread = useChatStore(selectUnreadTotal);
  const navigate = useNavigate();

  if (status !== 'signedIn') {
    return (
      <>
        <NavLink to="/login" className={navLinkClassName}>
          Log in
        </NavLink>
        <NavLink to="/signup" className={navLinkClassName}>
          Sign up
        </NavLink>
      </>
    );
  }

  // Home first, so a guarded page does not see the session end and send
  // the user to login with a "come back here" they did not ask for.
  function handleLogout() {
    void navigate('/', { replace: true });
    void logout();
  }

  return (
    <>
      <NavLink to="/trips" end className={navLinkClassName}>
        Trips
      </NavLink>
      <NavLink to="/trips/requests" className={navLinkClassName}>
        Requests
      </NavLink>
      <NavLink
        to="/messages"
        className={navLinkClassName}
        aria-label={unread > 0 ? `Messages, ${String(unread)} unread` : 'Messages'}
      >
        Messages
        {unread > 0 && (
          <span className="ml-1 rounded-full bg-white px-1.5 text-xs font-semibold text-teal-800">{unread}</span>
        )}
      </NavLink>
      <NavLink to="/reviews" className={navLinkClassName}>
        Reviews
      </NavLink>
      <NavLink to="/account/trust-score" className={navLinkClassName}>
        Trust score
      </NavLink>
      <NavLink to="/vibe" end className={navLinkClassName}>
        My vibe
      </NavLink>
      <NavLink to="/account/verification" className={navLinkClassName}>
        Verification
      </NavLink>
      <NavLink to="/account/emergency-contact" className={navLinkClassName}>
        Emergency contact
      </NavLink>
      <NavLink to="/account/upi-id" className={navLinkClassName}>
        UPI ID
      </NavLink>
      <NavLink to="/account/security" className={navLinkClassName}>
        Security
      </NavLink>
      <NavLink to="/account/sessions" className={navLinkClassName}>
        Sessions
      </NavLink>
      {isAdmin && (
        <NavLink to="/admin" className={navLinkClassName}>
          Moderation
        </NavLink>
      )}
      <button type="button" onClick={handleLogout} className="rounded px-2 py-1 text-sm font-medium hover:bg-teal-600">
        Log out
      </button>
    </>
  );
}
