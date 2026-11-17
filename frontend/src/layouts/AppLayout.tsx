import { Link, NavLink, Outlet } from 'react-router';
import { useAuthStore } from '../stores/authStore';

const navLinkClassName = ({ isActive }: { isActive: boolean }) =>
  `rounded px-2 py-1 text-sm font-medium hover:bg-teal-600 ${isActive ? 'bg-teal-800' : ''}`;

export function AppLayout() {
  const confirmed = useAuthStore((state) => state.confirmedCredentials);
  const clearSession = useAuthStore((state) => state.clearSession);

  return (
    <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
      <header className="bg-teal-700 text-white shadow">
        <nav className="mx-auto flex max-w-5xl items-center gap-2 px-4 py-3">
          <Link to="/" className="mr-auto text-lg font-semibold tracking-tight">
            TripMates
          </Link>
          {confirmed === null ? (
            <>
              <NavLink to="/login" className={navLinkClassName}>
                Log in
              </NavLink>
              <NavLink to="/signup" className={navLinkClassName}>
                Sign up
              </NavLink>
            </>
          ) : (
            <button
              type="button"
              onClick={clearSession}
              className="rounded px-2 py-1 text-sm font-medium hover:bg-teal-600"
            >
              Log out
            </button>
          )}
        </nav>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <Outlet />
      </main>
    </div>
  );
}
