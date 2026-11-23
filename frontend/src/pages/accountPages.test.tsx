// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, signIn } from '../test/renderApp';

const PASSWORD = 'violet-kettle-harbour-91';
const SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';
const BACKUP_CODES = ['7KQ2M-X9PDA', 'R3T8N-2WQXC', 'M4P7K-HZ9TB', 'F2V6J-8QWRN', 'X9C4D-T7MKP',
  'B8N2W-6RQJH', 'K5T9P-3XMZC', 'W7H4R-9JNDB', 'Q2D8F-5VTKM', 'Z6M3X-4PWRJ'];
const REAUTH_FAILED = 'The password or code is incorrect.';
const ADMIN_KEEPS_2FA = 'Admin accounts cannot turn off two-factor authentication.';

const server = createApiServer();

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installSessionHandling();
});
beforeEach(() => {
  useAuthStore.getState().clearSession();
  server.use(apiPost(REFRESH_PATH, () => problem(401, 'invalid_refresh_token', 'Your session has ended.')));
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

function session(id: string, device: string, current: boolean) {
  return {
    id,
    device,
    ipAddress: '203.0.113.7',
    signedInAt: '2026-11-19T08:00:00Z',
    lastActiveAt: '2026-11-19T09:30:00Z',
    expiresAt: '2026-12-19T08:00:00Z',
    current,
  };
}

describe('sessions screen', () => {
  it('lists the sessions with the current one marked as this device', async () => {
    const seen: (string | null)[] = [];
    server.use(
      apiGet('/auth/sessions', ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return HttpResponse.json({
          items: [session('s-1', 'Chrome on Windows', true), session('s-2', 'Firefox on Linux', false)],
        });
      }),
    );
    const token = signIn();

    renderAt('/account/sessions');

    const items = await screen.findAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]?.getAttribute('aria-current')).toBe('true');
    expect(within(items[0] ?? document.body).getByText('This device')).toBeDefined();
    expect(items[1]?.getAttribute('aria-current')).toBeNull();
    expect(within(items[1] ?? document.body).queryByText('This device')).toBeNull();
    expect(items[1]?.textContent).toContain('Firefox on Linux');
    expect(seen).toEqual([`Bearer ${token}`]);
  });

  it('renders server text as text, never as markup', async () => {
    server.use(
      apiGet('/auth/sessions', () =>
        HttpResponse.json({ items: [session('s-1', '<img src=x onerror=alert(1)>', true)] }),
      ),
    );
    signIn();

    renderAt('/account/sessions');

    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeDefined();
    expect(document.querySelector('img')).toBeNull();
  });

  it('shows a load failure through the normaliser and retries on request', async () => {
    let attempts = 0;
    server.use(
      apiGet('/auth/sessions', () => {
        attempts++;
        return attempts === 1
          ? HttpResponse.json({ status: 500, detail: 'stack trace here', correlationId: 'corr-1' }, { status: 500 })
          : HttpResponse.json({ items: [session('s-1', 'Chrome on Windows', true)] });
      }),
    );
    signIn();
    renderAt('/account/sessions');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Something went wrong on our side.');
    expect(document.body.textContent).not.toContain('stack trace here');

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('This device')).toBeDefined();
  });

  it('signs this tab out after revoking every device, and says why', async () => {
    const calls: Request[] = [];
    server.use(
      apiGet('/auth/sessions', () => HttpResponse.json({ items: [session('s-1', 'Chrome on Windows', true)] })),
      apiPost('/auth/sessions/revoke-all', ({ request }) => {
        calls.push(request);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    signIn();
    const router = renderAt('/account/sessions');
    await screen.findByText('This device');

    fireEvent.click(screen.getByRole('button', { name: 'Sign out of every device' }));
    expect(calls).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Yes, sign out everywhere' }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
    expect(calls).toHaveLength(1);
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(
      await screen.findByText('You signed out of every device. Log in again to continue on this one.'),
    ).toBeDefined();
  });

  it('keeps the user signed in and explains when revoke-all fails', async () => {
    server.use(
      apiGet('/auth/sessions', () => HttpResponse.json({ items: [session('s-1', 'Chrome on Windows', true)] })),
      apiPost('/auth/sessions/revoke-all', () =>
        problem(503, 'temporarily_unavailable', 'TripMates is temporarily unavailable.', {
          headers: { 'Retry-After': '30' },
        }),
      ),
    );
    signIn();
    const router = renderAt('/account/sessions');
    await screen.findByText('This device');

    fireEvent.click(screen.getByRole('button', { name: 'Sign out of every device' }));
    fireEvent.click(screen.getByRole('button', { name: 'Yes, sign out everywhere' }));

    expect((await screen.findByRole('alert')).textContent).toContain('TripMates is temporarily unavailable.');
    expect(router.state.location.pathname).toBe('/account/sessions');
    expect(useAuthStore.getState().status).toBe('signedIn');
  });
});

describe('security screen', () => {
  function statusEndpoint(state: { enabled: boolean; setupPending: boolean; backupCodesRemaining: number }) {
    return apiGet('/auth/2fa', () => HttpResponse.json(state));
  }

  it('sets up 2FA: password, the key for manual entry, a first code, then the backup codes once', async () => {
    let enabled = false;
    const bodies: unknown[] = [];
    server.use(
      apiGet('/auth/2fa', () =>
        HttpResponse.json({ enabled, setupPending: false, backupCodesRemaining: enabled ? 10 : 0 }),
      ),
      apiPost('/auth/2fa/setup', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({
          secret: SECRET,
          otpauthUri: `otpauth://totp/TripMates:asha%40example.com?secret=${SECRET}&issuer=TripMates`,
          issuer: 'TripMates',
          accountName: 'asha@example.com',
          algorithm: 'SHA1',
          digits: 6,
          period: 30,
        });
      }),
      apiPost('/auth/2fa/confirm', async ({ request }) => {
        bodies.push(await request.json());
        enabled = true;
        return HttpResponse.json({ backupCodes: BACKUP_CODES });
      }),
    );
    signIn();
    renderAt('/account/security');

    fireEvent.click(await screen.findByRole('button', { name: 'Set up two-factor authentication' }));
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: PASSWORD } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect((await screen.findByTestId('totp-secret')).textContent).toBe('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP');
    expect(screen.getByText(/^otpauth:\/\/totp\/TripMates/)).toBeDefined();
    fireEvent.change(screen.getByLabelText('Code from the app'), { target: { value: '492039' } });
    fireEvent.click(screen.getByRole('button', { name: 'Turn on two-factor authentication' }));

    const codes = await screen.findByRole('list', { name: 'Backup codes' });
    expect(within(codes).getAllByRole('listitem').map((item) => item.textContent)).toEqual(BACKUP_CODES);
    expect(screen.getByText('Save these backup codes now. They will not be shown again.')).toBeDefined();
    expect(document.body.textContent).not.toContain(SECRET);
    expect(bodies).toEqual([{ password: PASSWORD }, { code: '492039' }]);

    fireEvent.click(screen.getByRole('button', { name: 'I have saved my backup codes' }));
    expect(await screen.findByText(/Backup codes left:/)).toBeDefined();
    expect(screen.queryByText(BACKUP_CODES[0] ?? '')).toBeNull();
  });

  it('shows a wrong first code as the server words it and stays on the confirm step', async () => {
    server.use(
      statusEndpoint({ enabled: false, setupPending: false, backupCodesRemaining: 0 }),
      apiPost('/auth/2fa/setup', () =>
        HttpResponse.json({
          secret: SECRET,
          otpauthUri: `otpauth://totp/TripMates:a?secret=${SECRET}`,
          issuer: 'TripMates',
          accountName: 'a',
        }),
      ),
      apiPost('/auth/2fa/confirm', () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'invalid_totp_code',
            detail: 'That code does not match. Check the time on your phone and enter the current code.',
            errors: [{ field: 'code', message: 'That code does not match. Check the time on your phone and enter the current code.' }],
          },
          { status: 400 },
        ),
      ),
    );
    signIn();
    renderAt('/account/security');
    fireEvent.click(await screen.findByRole('button', { name: 'Set up two-factor authentication' }));
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: PASSWORD } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByTestId('totp-secret');

    fireEvent.change(screen.getByLabelText('Code from the app'), { target: { value: '000000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Turn on two-factor authentication' }));

    expect(
      await screen.findByText('That code does not match. Check the time on your phone and enter the current code.'),
    ).toBeDefined();
    expect(screen.getByTestId('totp-secret')).toBeDefined();
  });

  it('refuses a wrong password at setup with the one shared message', async () => {
    server.use(
      statusEndpoint({ enabled: false, setupPending: true, backupCodesRemaining: 0 }),
      apiPost('/auth/2fa/setup', () => problem(400, 'reauthentication_failed', REAUTH_FAILED)),
    );
    signIn();
    renderAt('/account/security');

    expect(await screen.findByText(/A setup was started but not finished/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Set up two-factor authentication' }));
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));

    expect((await screen.findByRole('alert')).textContent).toContain(REAUTH_FAILED);
    expect(screen.getByLabelText<HTMLInputElement>('Password').value).toBe('');
  });

  it('replaces the backup codes and shows the new set once', async () => {
    const bodies: unknown[] = [];
    server.use(
      statusEndpoint({ enabled: true, setupPending: false, backupCodesRemaining: 2 }),
      apiPost('/auth/2fa/backup-codes', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ backupCodes: BACKUP_CODES });
      }),
    );
    signIn({ amr: ['pwd', 'otp'] });
    renderAt('/account/security');

    expect(await screen.findByText(/consider creating new ones/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Create new backup codes' }));
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: PASSWORD } });
    fireEvent.change(screen.getByLabelText('Code from your app, or a backup code'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create new codes' }));

    expect(await screen.findByRole('list', { name: 'Backup codes' })).toBeDefined();
    expect(bodies).toEqual([{ password: PASSWORD, code: '123456' }]);
  });

  it('turns 2FA off with the password and a code', async () => {
    let enabled = true;
    server.use(
      apiGet('/auth/2fa', () => HttpResponse.json({ enabled, setupPending: false, backupCodesRemaining: 10 })),
      apiPost('/auth/2fa/disable', () => {
        enabled = false;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    signIn({ amr: ['pwd', 'otp'] });
    renderAt('/account/security');

    fireEvent.click(await screen.findByRole('button', { name: 'Turn off' }));
    fireEvent.click(screen.getByRole('button', { name: 'Turn off two-factor authentication' }));
    expect(await screen.findByText('Enter your password.')).toBeDefined();

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: PASSWORD } });
    fireEvent.change(screen.getByLabelText('Code from your app, or a backup code'), {
      target: { value: '7KQ2M-X9PDA' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Turn off two-factor authentication' }));

    expect(await screen.findByRole('button', { name: 'Set up two-factor authentication' })).toBeDefined();
  });

  it('explains that an admin cannot turn 2FA off', async () => {
    server.use(
      statusEndpoint({ enabled: true, setupPending: false, backupCodesRemaining: 10 }),
      apiPost('/auth/2fa/disable', () => problem(403, 'two_factor_required_for_admin', ADMIN_KEEPS_2FA)),
    );
    signIn({ role: 'ADMIN', amr: ['pwd', 'otp'] });
    renderAt('/account/security');

    fireEvent.click(await screen.findByRole('button', { name: 'Turn off' }));
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: PASSWORD } });
    fireEvent.change(screen.getByLabelText('Code from your app, or a backup code'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Turn off two-factor authentication' }));

    expect((await screen.findByRole('alert')).textContent).toContain(ADMIN_KEEPS_2FA);
  });
});
