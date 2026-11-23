import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { installAuthInterceptors } from '../api/authRefresh';
import { apiClient } from '../api/client';
import { routes } from '../router';
import { provideSessionRefresher, useAuthStore, type AuthMethod, type UserRole } from '../stores/authStore';
import { fakeAccessToken } from './apiServer';

export const SIGNED_IN_USER_ID = '0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c';

/** The interceptors and start-up refresher main.tsx installs. Call once per test file. */
export function installSessionHandling(): void {
  provideSessionRefresher(
    installAuthInterceptors(apiClient, {
      onSessionExpired: () => {
        useAuthStore.getState().clearSession('expired');
      },
    }),
  );
}

export function renderAt(pathname: string, state?: unknown) {
  const router = createMemoryRouter(routes, { initialEntries: [{ pathname, state }] });
  render(<RouterProvider router={router} />);
  return router;
}

export function signIn(options: { role?: UserRole; amr?: readonly AuthMethod[]; tokenId?: string } = {}): string {
  const amr = options.amr ?? ['pwd'];
  const accessToken = fakeAccessToken(amr, options.tokenId);
  useAuthStore.getState().setSession({
    accessToken,
    user: { id: SIGNED_IN_USER_ID, role: options.role ?? 'USER', verificationTier: 'EMAIL' },
    authMethods: amr,
  });
  return accessToken;
}

export function heading(): string | null {
  return screen.getByRole('heading', { level: 1 }).textContent;
}
