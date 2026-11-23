// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from './router';
import { useAuthStore } from './stores/authStore';

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
}

describe('routes', () => {
  beforeEach(() => {
    useAuthStore.getState().clearSession();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders the home page inside the layout', () => {
    renderAt('/');

    expect(screen.getByRole('link', { name: 'TripMates' })).toBeDefined();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Travel with strangers you can trust');
  });

  it.each([
    ['/signup', 'Create your account'],
    ['/verify-email', 'Verify your email'],
    ['/login', 'Log in'],
  ])('renders %s', (path, heading) => {
    renderAt(path);

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(heading);
  });

  it.each(['/account/security', '/account/sessions', '/admin/users'])(
    'sends a signed-out visitor from %s to login',
    (path) => {
      renderAt(path);

      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Log in');
    },
  );

  it('shows only a loading screen until the start-up refresh answers', () => {
    useAuthStore.setState({ status: 'starting' });

    renderAt('/');

    expect(screen.getByRole('status').textContent).toBe('Loading TripMates…');
    expect(screen.queryByRole('heading')).toBeNull();
  });

  it('renders the not-found page for an unknown path', () => {
    renderAt('/no/such/page');

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Page not found');
    expect(screen.getByRole('link', { name: 'Back to home' }).getAttribute('href')).toBe('/');
  });
});
