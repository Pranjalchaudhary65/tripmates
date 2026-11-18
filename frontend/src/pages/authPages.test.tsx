// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../router';
import { useAuthStore } from '../stores/authStore';
import { apiPost, authenticated, createApiServer } from '../test/apiServer';

const CHALLENGE_MESSAGE = 'If this email address can be used, a verification code has been sent to it.';
const INVALID_CREDENTIALS =
  'The email or password is incorrect, or the email address has not been verified yet. ' +
  'If you just signed up, use the code we emailed you first.';
const UNDERAGE = 'You must be at least 18 years old to use TripMates.';
const FIRST_ID = '5b0c6f1e-6a3e-4c55-9d7a-1f2e3d4c5b6a';
const SECOND_ID = '9e8d7c6b-5a49-4382-a716-1b2c3d4e5f60';
const CORRELATION_ID = 'c0ffee00-1234-4abc-8def-0123456789ab';

const server = createApiServer();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
});
beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null, confirmedCredentials: null, pendingVerification: null });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function renderAt(pathname: string, state?: unknown) {
  const router = createMemoryRouter(routes, { initialEntries: [{ pathname, state }] });
  render(<RouterProvider router={router} />);
  return router;
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function errorTextOf(label: string): string | null {
  const describedBy = screen.getByLabelText(label).getAttribute('aria-describedby') ?? '';
  const errorId = describedBy.split(' ').find((id) => id.endsWith('-error'));
  return errorId === undefined ? null : (document.getElementById(errorId)?.textContent ?? null);
}

function fillValidSignup() {
  type('Email', 'asha.verma@example.com');
  type('Full name', 'Asha Verma');
  type('Password', 'violet-kettle-harbour-91');
  type('Date of birth', '2000-04-15');
  type('Gender', 'FEMALE');
}

describe('signup screen', () => {
  it('shows client-side errors beside each field and sends nothing', async () => {
    const calls: Request[] = [];
    server.use(
      apiPost('/auth/register', ({ request }) => {
        calls.push(request);
        return HttpResponse.json({}, { status: 500 });
      }),
    );
    renderAt('/signup');

    type('Password', 'short');
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Enter your email address.')).toBeDefined();
    expect(errorTextOf('Password')).toBe('Use at least 12 characters.');
    expect(screen.getByLabelText('Password').getAttribute('aria-invalid')).toBe('true');
    expect(errorTextOf('Gender')).toBe('Choose an option.');
    expect(calls).toHaveLength(0);
  });

  it('puts a server field error beside its field, not in the generic notice', async () => {
    server.use(
      apiPost('/auth/register', () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'underage',
            detail: UNDERAGE,
            correlationId: CORRELATION_ID,
            errors: [{ field: 'dateOfBirth', message: UNDERAGE }],
          },
          { status: 400 },
        ),
      ),
    );
    renderAt('/signup');
    fillValidSignup();

    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => {
      expect(errorTextOf('Date of birth')).toBe(UNDERAGE);
    });
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Check the highlighted fields');
    expect(alert.textContent).not.toContain(UNDERAGE);
  });

  it('sends one request however fast the button is clicked, then moves to the verify screen', async () => {
    const bodies: unknown[] = [];
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      apiPost('/auth/register', async ({ request }) => {
        bodies.push(await request.json());
        await gate;
        return HttpResponse.json({ verificationId: FIRST_ID, message: CHALLENGE_MESSAGE }, { status: 202 });
      }),
    );
    const router = renderAt('/signup');
    fillValidSignup();

    const form = screen.getByRole('button', { name: 'Create account' }).closest('form');
    if (form === null) {
      throw new Error('Signup form not found.');
    }
    fireEvent.submit(form);
    fireEvent.submit(form);
    fireEvent.submit(form);

    const button = await screen.findByRole('button', { name: 'Creating account…' });
    expect(button.hasAttribute('disabled')).toBe(true);
    release();

    expect(await screen.findByText(CHALLENGE_MESSAGE)).toBeDefined();
    expect(router.state.location.pathname).toBe('/verify-email');
    expect(router.state.location.search).toBe('');
    expect(bodies).toHaveLength(1);
  });

  it('shows the generic message and a reference for a server error, never its body', async () => {
    server.use(
      apiPost('/auth/register', () =>
        HttpResponse.json(
          { status: 500, detail: 'PSQLException: relation users does not exist', correlationId: CORRELATION_ID },
          { status: 500 },
        ),
      ),
    );
    renderAt('/signup');
    fillValidSignup();

    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Something went wrong on our side.');
    expect(alert.textContent).toContain(CORRELATION_ID);
    expect(document.body.textContent).not.toContain('PSQLException');
  });

  it('tells the user when the network is down', async () => {
    server.use(apiPost('/auth/register', () => HttpResponse.error()));
    renderAt('/signup');
    fillValidSignup();

    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Could not reach TripMates');
  });
});

describe('verify-email screen', () => {
  it('shows an empty state with a way back when there is no sign-up in this tab', () => {
    renderAt('/verify-email');

    expect(screen.getByText(/no sign-up waiting for a code/)).toBeDefined();
    expect(screen.getByRole('link', { name: 'Sign up again' }).getAttribute('href')).toBe('/signup');
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  it('shows a wrong code beside the input and keeps the user on the screen', async () => {
    useAuthStore.setState({
      pendingVerification: {
        verificationId: FIRST_ID,
        email: 'asha.verma@example.com',
        message: CHALLENGE_MESSAGE,
        codeRequestedAt: Date.now(),
      },
    });
    const detail = 'The verification code is invalid or has expired. Check the code or request a new one.';
    server.use(
      apiPost('/auth/verify-email', () =>
        HttpResponse.json(
          { status: 400, code: 'invalid_verification_code', detail, errors: [{ field: 'code', message: detail }] },
          { status: 400 },
        ),
      ),
    );
    renderAt('/verify-email');

    type('Verification code', '12a34-56');
    expect(screen.getByLabelText<HTMLInputElement>('Verification code').value).toBe('123456');
    fireEvent.click(screen.getByRole('button', { name: 'Verify email' }));

    await waitFor(() => {
      expect(errorTextOf('Verification code')).toBe(detail);
    });
  });

  it('holds resend back during the cooldown, then swaps in the new verificationId', async () => {
    useAuthStore.setState({
      pendingVerification: {
        verificationId: FIRST_ID,
        email: 'asha.verma@example.com',
        message: CHALLENGE_MESSAGE,
        codeRequestedAt: Date.now() - 61_000,
      },
    });
    const resendBodies: unknown[] = [];
    server.use(
      apiPost('/auth/verify-email/resend', async ({ request }) => {
        resendBodies.push(await request.json());
        return HttpResponse.json({ verificationId: SECOND_ID, message: CHALLENGE_MESSAGE }, { status: 202 });
      }),
    );
    renderAt('/verify-email');

    fireEvent.click(screen.getByRole('button', { name: 'Send a new code' }));

    expect(await screen.findByText(/codes sent before it no longer work/)).toBeDefined();
    expect(resendBodies).toEqual([{ verificationId: FIRST_ID }]);
    expect(useAuthStore.getState().pendingVerification?.verificationId).toBe(SECOND_ID);
    const resend = screen.getByRole('button', { name: /Send a new code in \d+s/ });
    expect(resend.hasAttribute('disabled')).toBe(true);
  });

  it('sends the user to log in once the email is verified', async () => {
    useAuthStore.setState({
      pendingVerification: {
        verificationId: FIRST_ID,
        email: 'asha.verma@example.com',
        message: CHALLENGE_MESSAGE,
        codeRequestedAt: Date.now(),
      },
    });
    server.use(
      apiPost('/auth/verify-email', () => HttpResponse.json({ emailVerified: true, verificationTier: 'EMAIL' })),
    );
    const router = renderAt('/verify-email');

    type('Verification code', '482913');
    fireEvent.click(screen.getByRole('button', { name: 'Verify email' }));

    expect(await screen.findByText('Your email address is verified. You can log in now.')).toBeDefined();
    expect(router.state.location.pathname).toBe('/login');
    expect(useAuthStore.getState().pendingVerification).toBeNull();
  });
});

describe('login screen', () => {
  it('shows exactly the message the API sends for a refused login and clears the password', async () => {
    server.use(
      apiPost('/auth/login', () =>
        HttpResponse.json(
          { status: 401, code: 'invalid_credentials', detail: INVALID_CREDENTIALS, correlationId: CORRELATION_ID },
          { status: 401 },
        ),
      ),
    );
    renderAt('/login');
    type('Email', 'asha@example.com');
    type('Password', 'wrong-password');

    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(INVALID_CREDENTIALS);
    expect(screen.getByLabelText<HTMLInputElement>('Password').value).toBe('');
  });

  it('counts down from Retry-After on a 429 and keeps the button disabled until it ends', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    const calls: Request[] = [];
    server.use(
      apiPost('/auth/login', ({ request }) => {
        calls.push(request);
        return HttpResponse.json(
          { status: 429, code: 'rate_limited', detail: 'Wait for the number of seconds in the Retry-After header.' },
          { status: 429, headers: { 'Retry-After': '37' } },
        );
      }),
    );
    renderAt('/login');
    type('Email', 'asha@example.com');
    type('Password', 'pw');

    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    const waiting = await screen.findByRole('button', { name: 'Try again in 37s' });
    expect(waiting.hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('alert').textContent).toContain('You can try again in 37 seconds.');
    expect(document.body.textContent).not.toContain('Retry-After header');

    fireEvent.submit(waiting.closest('form') ?? waiting);
    expect(calls).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(screen.getByRole('button', { name: 'Try again in 7s' }).hasAttribute('disabled')).toBe(true);

    act(() => {
      vi.advanceTimersByTime(7_500);
    });
    expect(screen.getByRole('button', { name: 'Log in' }).hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('alert').textContent).toContain('You can try again now.');
  });

  it('confirms the credentials and goes home without creating a session', async () => {
    server.use(
      apiPost('/auth/login', () =>
        HttpResponse.json(authenticated('0d4a3c1e-8f7b-4e2a-9c6d-5b4a3f2e1d0c', 'EMAIL')),
      ),
    );
    const router = renderAt('/login');
    type('Email', 'asha@example.com');
    type('Password', 'violet-kettle-harbour-91');

    fireEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByText('Your email and password were accepted.')).toBeDefined();
    expect(screen.getByText('Verification level: Email verified')).toBeDefined();
    expect(router.state.location.pathname).toBe('/');
    expect(useAuthStore.getState().accessToken).toBeNull();
  });
});
