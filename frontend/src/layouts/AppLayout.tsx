import { Link, Outlet } from 'react-router';

export function AppLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
      <header className="bg-teal-700 text-white shadow">
        <nav className="mx-auto flex max-w-5xl items-center px-4 py-3">
          <Link to="/" className="text-lg font-semibold tracking-tight">
            TripMates
          </Link>
        </nav>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <Outlet />
      </main>
    </div>
  );
}
