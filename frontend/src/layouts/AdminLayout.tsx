import { NavLink, Outlet } from 'react-router';

const tabClassName = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-1.5 text-sm font-medium ${
    isActive ? 'bg-teal-700 text-white' : 'text-teal-800 hover:bg-teal-50'
  }`;

/**
 * The moderation panel's own navigation, above every admin screen. Rendered
 * only inside RequireAdmin, so it never shows to anyone else; the server
 * still answers 403 on every /admin route for a session without ADMIN and a
 * second factor, whatever this renders.
 */
export function AdminLayout() {
  return (
    <div>
      <nav aria-label="Moderation" className="mb-6 flex flex-wrap gap-2 border-b border-slate-200 pb-3">
        <NavLink to="/admin/reports" className={tabClassName}>
          Reports
        </NavLink>
        <NavLink to="/admin/verifications" className={tabClassName}>
          Selfie reviews
        </NavLink>
        <NavLink to="/admin/users" className={tabClassName}>
          Accounts
        </NavLink>
        <NavLink to="/admin/audit" className={tabClassName}>
          Audit log
        </NavLink>
        <NavLink to="/admin/data-requests" className={tabClassName}>
          Data requests
        </NavLink>
      </nav>
      <Outlet />
    </div>
  );
}
