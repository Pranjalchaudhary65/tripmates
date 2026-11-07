import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <section>
      <h1 className="text-2xl font-bold">Page not found</h1>
      <p className="mt-2 text-slate-600">The page you are looking for does not exist.</p>
      <Link to="/" className="mt-4 inline-block font-medium text-teal-700 underline">
        Back to home
      </Link>
    </section>
  );
}
