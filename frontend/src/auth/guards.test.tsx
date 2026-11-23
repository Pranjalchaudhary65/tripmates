// @vitest-environment jsdom
import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { accessGrant, apiGet, apiPost, authenticated, createApiServer, problem } from '../test/apiServer';
import { heading, installSessionHandling, renderAt, signIn, SIGNED_IN_USER_ID } from '../test/renderApp';

const server = createApiServer();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiGet('/auth/sessions', () => HttpResponse.json({ items: [] })));
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

/** A refresh endpoint that holds its answer until released, like a slow network on page load. */
function gatedRefresh(answer: () => Response) {
  const calls: Request[] = [];
  let release: () => void = () => undefined;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  server.use(
    apiPost(REFRESH_PATH, async ({ request }) => {
      calls.push(request);
      await gate;
      return answer();
    }),
  );
  return { calls, release };
}

describe('while the start-up refresh is in flight', () => {
  it('shows a loading screen on a guarded route and does not redirect', async () => {
    const refresh = gatedRefresh(() => problem(401, 'invalid_refresh_token', 'Your session has ended.'));
    useAuthStore.setState({ status: 'starting' });
    const restoring = useAuthStore.getState().restoreSession();

    const router = renderAt('/account/sessions');

    expect(screen.getByRole('status').textContent).toBe('Loading TripMates…');
    expect(screen.queryByRole('link', { name: 'Log in' })).toBeNull();
    await waitFor(() => {
      expect(refresh.calls).toHaveLength(1);
    });
    expect(screen.getByRole('status').textContent).toBe('Loading TripMates…');
    expect(router.state.location.pathname).toBe('/account/sessions');

    refresh.release();
    await act(() => restoring);

    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ from: '/account/sessions' });
    expect(useAuthStore.getState().signOutReason).toBeNull();
  });

  it('opens the guarded route straight away once the refresh restores the session', async () => {
    const refresh = gatedRefresh(() => HttpResponse.json(accessGrant(SIGNED_IN_USER_ID, 'EMAIL')));
    useAuthStore.setState({ status: 'starting' });
    const restoring = useAuthStore.getState().restoreSession();

    const router = renderAt('/account/sessions');
    refresh.release();
    await act(() => restoring);

    expect(await screen.findByRole('heading', { name: 'Signed-in devices' })).toBeDefined();
    expect(router.state.location.pathname).toBe('/account/sessions');
  });

  it('does not flash the login form for a signed-in user who reloads /login', async () => {
    const refresh = gatedRefresh(() => HttpResponse.json(accessGrant(SIGNED_IN_USER_ID, 'EMAIL')));
    useAuthStore.setState({ status: 'starting' });
    const restoring = useAuthStore.getState().restoreSession();

    const router = renderAt('/login');
    expect(screen.queryByLabelText('Email')).toBeNull();
    refresh.release();
    await act(() => restoring);

    expect(router.state.location.pathname).toBe('/');
  });
});

describe('when the start-up refresh gets no answer', () => {
  it('keeps waiting instead of signing out, and tries again after Retry-After', async () => {
    const calls: Request[] = [];
    let available = false;
    server.use(
      apiPost(REFRESH_PATH, ({ request }) => {
        calls.push(request);
        return available
          ? HttpResponse.json(accessGrant(SIGNED_IN_USER_ID, 'EMAIL'))
          : problem(503, 'temporarily_unavailable', 'Busy.', { headers: { 'Retry-After': '1' } });
      }),
    );
    useAuthStore.setState({ status: 'starting' });
    await useAuthStore.getState().restoreSession();
    const router = renderAt('/account/sessions');

    expect(heading()).toBe('TripMates is temporarily unavailable');
    expect(screen.getByText('Trying again in 1 second.')).toBeDefined();
    expect(router.state.location.pathname).toBe('/account/sessions');
    expect(useAuthStore.getState().status).toBe('unavailable');

    available = true;
    await waitFor(
      () => {
        expect(useAuthStore.getState().status).toBe('signedIn');
      },
      { timeout: 3000 },
    );
    expect(calls).toHaveLength(2);
    expect(router.state.location.pathname).toBe('/account/sessions');
  });
});

describe('RequireSession', () => {
  it('sends a signed-out visitor to login and back to the page afterwards', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(SIGNED_IN_USER_ID, 'EMAIL'))));

    const router = renderAt('/account/sessions');
    expect(router.state.location.pathname).toBe('/login');

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'asha@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'violet-kettle-harbour-91' } });
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/account/sessions');
    });
  });

  it('sends the user to login with a notice when a refresh finds the session gone', async () => {
    server.use(
      apiGet('/auth/sessions', () => problem(401, 'unauthorized', 'Authentication is required.')),
      apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')),
    );
    signIn();

    const router = renderAt('/account/sessions');

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(await screen.findByText('Your session has ended. Log in again to continue.')).toBeDefined();
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});

describe('GuestOnly', () => {
  it('sends a signed-in user away from login', () => {
    signIn();

    const router = renderAt('/login');

    expect(router.state.location.pathname).toBe('/');
  });

  it('never follows a return path that leaves the app', async () => {
    server.use(apiPost('/auth/login', () => HttpResponse.json(authenticated(SIGNED_IN_USER_ID, 'EMAIL'))));
    const router = renderAt('/login', { from: '//evil.example/steal' });

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'asha@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'violet-kettle-harbour-91' } });
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/');
    });
  });
});

describe('RequireAdmin', () => {
  it('turns away a signed-in user who is not an admin', () => {
    signIn({ role: 'USER', amr: ['pwd', 'otp'] });

    renderAt('/admin/users');

    expect(heading()).toBe('Not available');
    expect(screen.getByText('This area is for TripMates moderators.')).toBeDefined();
  });

  it('asks an admin whose session skipped the second factor to sign in with it', () => {
    signIn({ role: 'ADMIN', amr: ['pwd'] });

    renderAt('/admin/users');

    expect(heading()).toBe('Not available');
    expect(screen.getByText(/need a sign-in with your authenticator app/)).toBeDefined();
  });

  it('opens for an admin signed in with a second factor', () => {
    signIn({ role: 'ADMIN', amr: ['pwd', 'otp'] });

    renderAt('/admin/users');

    expect(heading()).toBe('Find an account');
  });

  it('sends a signed-out visitor to login first', () => {
    const router = renderAt('/admin/users');

    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ from: '/admin/users' });
  });
});
