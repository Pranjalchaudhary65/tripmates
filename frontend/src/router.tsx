import { createBrowserRouter, type RouteObject } from 'react-router';
import { GuestOnly, RequireAdmin, RequireSession } from './auth/guards';
import { AppLayout } from './layouts/AppLayout';
import { AdminUserLookupPage } from './pages/AdminUserLookupPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { RouteErrorPage } from './pages/RouteErrorPage';
import { SecondFactorPage } from './pages/SecondFactorPage';
import { SecurityPage } from './pages/SecurityPage';
import { SessionsPage } from './pages/SessionsPage';
import { SignupPage } from './pages/SignupPage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';

/**
 * Public: home and the not-found page. Signed-out only: sign-up, email
 * verification and both login steps. Signed-in only: account screens.
 * Admin: signed in, role ADMIN and a second factor in this session.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <HomePage /> },
      {
        element: <GuestOnly />,
        children: [
          { path: 'signup', element: <SignupPage /> },
          { path: 'verify-email', element: <VerifyEmailPage /> },
          { path: 'login', element: <LoginPage /> },
          { path: 'login/second-factor', element: <SecondFactorPage /> },
        ],
      },
      {
        element: <RequireSession />,
        children: [
          { path: 'account/security', element: <SecurityPage /> },
          { path: 'account/sessions', element: <SessionsPage /> },
          {
            element: <RequireAdmin />,
            children: [{ path: 'admin/users', element: <AdminUserLookupPage /> }],
          },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
