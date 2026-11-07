// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { routes } from './router';

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
}

describe('routes', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders the home page inside the layout', () => {
    renderAt('/');

    expect(screen.getByRole('link', { name: 'TripMates' })).toBeDefined();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Travel with strangers you can trust');
  });

  it('renders the not-found page for an unknown path', () => {
    renderAt('/no/such/page');

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Page not found');
    expect(screen.getByRole('link', { name: 'Back to home' }).getAttribute('href')).toBe('/');
  });
});
