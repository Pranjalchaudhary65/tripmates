import { Link } from 'react-router';

/**
 * Shown when a route throws while rendering. It never displays the error
 * itself, so no stack trace or server message reaches the user.
 */
export function RouteErrorPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-slate-50 px-4 text-center text-slate-900">
      <h1 className="text-2xl font-bold">Something went wrong</h1>
      <p className="mt-2 text-slate-600">Please try again in a moment.</p>
      <Link to="/" className="mt-4 font-medium text-teal-700 underline">
        Back to home
      </Link>
    </div>
  );
}
