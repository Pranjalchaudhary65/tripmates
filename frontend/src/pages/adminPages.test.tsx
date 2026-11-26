// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import {
  adminUser,
  auditEntry,
  auditPage,
  check,
  dataRequest,
  page,
  report,
  REPORTED_ID,
} from '../test/adminFixtures';
import { apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { heading, installSessionHandling, renderAt, signIn, SIGNED_IN_USER_ID } from '../test/renderApp';

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

function signInAsModerator() {
  signIn({ role: 'ADMIN', amr: ['pwd', 'otp'] });
}

describe('moderation panel guard', () => {
  it.each(['/admin', '/admin/reports', '/admin/audit', '/admin/data-requests'])(
    'keeps %s closed to a user who is not an admin',
    (path) => {
      signIn({ role: 'USER', amr: ['pwd', 'otp'] });
      renderAt(path);

      expect(heading()).toBe('Not available');
    },
  );

  it('keeps it closed to an admin session without a second factor', () => {
    signIn({ role: 'ADMIN', amr: ['pwd'] });
    renderAt('/admin/reports');

    expect(screen.getByText(/need a sign-in with your authenticator app/)).toBeDefined();
  });

  it('opens on the report queue, with every moderation screen in its navigation', async () => {
    server.use(apiGet('/admin/reports', () => HttpResponse.json(page([]))));
    signInAsModerator();
    const router = renderAt('/admin');

    expect(await screen.findByRole('heading', { level: 1, name: 'Reports' })).toBeDefined();
    expect(router.state.location.pathname).toBe('/admin/reports');
    const nav = screen.getByRole('navigation', { name: 'Moderation' });
    expect([...nav.querySelectorAll('a')].map((link) => link.textContent)).toEqual([
      'Reports',
      'Selfie reviews',
      'Accounts',
      'Audit log',
      'Data requests',
    ]);
  });
});

describe('report queue', () => {
  async function openReports(items: ReturnType<typeof report>[]) {
    server.use(apiGet('/admin/reports', () => HttpResponse.json(page(items))));
    signInAsModerator();
    renderAt('/admin/reports');
    return screen.findByRole('heading', { level: 2, name: 'Harassment or abuse: about Ravi' });
  }

  it('shows the reason, the reporter, the details and how many people reported them', async () => {
    const card = (await openReports([report()])).closest('li');

    expect(card?.textContent).toContain('by Asha');
    expect(card?.textContent).toContain('Kept messaging after I said stop.');
    expect(card?.textContent).toContain('Reported by 2 different people');
    expect(screen.getByRole('link', { name: "Ravi's account" }).getAttribute('href')).toBe(
      `/admin/users?userId=${REPORTED_ID}`,
    );
    expect(screen.getByRole('link', { name: 'Audit trail for Ravi' }).getAttribute('href')).toBe(
      `/admin/audit?entityId=${REPORTED_ID}`,
    );
  });

  it('says when details were written but can no longer be read', async () => {
    await openReports([report({ details: null, detailsUnreadable: true })]);

    expect(screen.getByText(/the reporter deleted their account/)).toBeDefined();
  });

  it('says plainly when nothing is waiting', async () => {
    server.use(apiGet('/admin/reports', () => HttpResponse.json(page([]))));
    signInAsModerator();
    renderAt('/admin/reports');

    expect(await screen.findByText('No reports are waiting.')).toBeDefined();
  });

  it('takes action and suspends only after a confirm step and an explicit choice', async () => {
    let body: unknown = null;
    server.use(
      apiPost('/admin/reports/:id/decision', async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await openReports([report()]);

    fireEvent.click(screen.getByRole('button', { name: 'Take action on the report about Ravi' }));
    expect(body).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: /Also suspend Ravi/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm action' }));

    expect((await screen.findByRole('status')).textContent).toBe(
      "Closed as actioned. Ravi's account is suspended and their sessions have ended.",
    );
    expect(body).toEqual({ decision: 'ACTIONED', suspend: true });
    expect(screen.queryByRole('button', { name: 'Take action on the report about Ravi' })).toBeNull();
  });

  it('dismisses without suspending', async () => {
    let body: unknown = null;
    server.use(
      apiPost('/admin/reports/:id/decision', async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await openReports([report()]);

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss the report about Ravi' }));
    expect(screen.queryByRole('checkbox')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm dismissal' }));

    expect((await screen.findByRole('status')).textContent).toBe('Closed as dismissed.');
    expect(body).toEqual({ decision: 'DISMISSED', suspend: false });
  });

  it('offers no suspension for someone who is already not active', async () => {
    await openReports([report({}, { status: 'NOT_ACTIVE' })]);

    fireEvent.click(screen.getByRole('button', { name: 'Take action on the report about Ravi' }));

    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.getByText(/not active \(suspended, banned or deleted\)/)).toBeDefined();
  });

  it.each([
    ['cannot_moderate_self', 'You cannot decide about a report you are part of, or about your own account.'],
    ['report_closed', 'This report has already been closed.'],
  ])('shows %s on the card and does not claim it closed', async (code, detail) => {
    server.use(apiPost('/admin/reports/:id/decision', () => problem(409, code, detail)));
    await openReports([report()]);

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss the report about Ravi' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm dismissal' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
    expect(screen.queryByText('Closed as dismissed.')).toBeNull();
  });

  it('lists closed reports by state, without decision buttons', async () => {
    const asked: (string | null)[] = [];
    server.use(
      apiGet('/admin/reports', ({ request }) => {
        const status = new URL(request.url).searchParams.get('status');
        asked.push(status);
        return HttpResponse.json(page(status === 'DISMISSED' ? [report({ status: 'DISMISSED' })] : []));
      }),
    );
    signInAsModerator();
    renderAt('/admin/reports');
    await screen.findByText('No reports are waiting.');

    fireEvent.click(screen.getByRole('button', { name: 'Dismissed' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Harassment or abuse: about Ravi' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Dismiss the report about Ravi' })).toBeNull();
    expect(asked).toEqual(['OPEN', 'DISMISSED']);
  });

  it('refuses a report the server should never send, with the generic error', async () => {
    server.use(apiGet('/admin/reports', () => HttpResponse.json(page([report({ reason: 'SAFETY_CANCELLATION' })]))));
    signInAsModerator();
    renderAt('/admin/reports');

    expect((await screen.findByRole('alert')).textContent).toContain('Something went wrong. Please try again.');
    expect(screen.queryByText('Kept messaging after I said stop.')).toBeNull();
  });

  it('shows a failure to load with its reference and retries', async () => {
    let attempts = 0;
    server.use(
      apiGet('/admin/reports', () => {
        attempts++;
        return attempts === 1 ? problem(403, 'forbidden', 'Forbidden') : HttpResponse.json(page([]));
      }),
    );
    signInAsModerator();
    renderAt('/admin/reports');

    expect((await screen.findByRole('alert')).textContent).toContain('You do not have permission to do that.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('No reports are waiting.')).toBeDefined();
  });
});

describe('accounts', () => {
  it('opens the account named in the address and suspends it after confirming, then shows the real state', async () => {
    let status = 'ACTIVE';
    let suspended = 0;
    server.use(
      apiGet('/admin/users/:id', () => HttpResponse.json(adminUser({ accountStatus: status }))),
      apiPost('/admin/users/:id/suspend', () => {
        suspended++;
        status = 'SUSPENDED';
        return new HttpResponse(null, { status: 204 });
      }),
    );
    signInAsModerator();
    renderAt(`/admin/users?userId=${REPORTED_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Suspend account' }));
    expect(suspended).toBe(0);
    expect(screen.getByText(/Every session they have ends now/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm suspension' }));

    expect(await screen.findByRole('button', { name: 'Reinstate account' })).toBeDefined();
    expect(screen.getByText('SUSPENDED')).toBeDefined();
    expect(suspended).toBe(1);
  });

  it('reinstates a suspended account', async () => {
    let reinstated = false;
    server.use(
      apiGet('/admin/users/:id', () =>
        HttpResponse.json(adminUser({ accountStatus: reinstated ? 'ACTIVE' : 'SUSPENDED' })),
      ),
      apiPost('/admin/users/:id/reinstate', () => {
        reinstated = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    signInAsModerator();
    renderAt(`/admin/users?userId=${REPORTED_ID}`);

    fireEvent.click(await screen.findByRole('button', { name: 'Reinstate account' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm reinstatement' }));

    expect(await screen.findByRole('button', { name: 'Suspend account' })).toBeDefined();
  });

  it('offers nothing to do on the moderator’s own account', async () => {
    server.use(apiGet('/admin/users/:id', () => HttpResponse.json(adminUser({ id: SIGNED_IN_USER_ID }))));
    signInAsModerator();
    renderAt(`/admin/users?userId=${SIGNED_IN_USER_ID}`);

    expect(await screen.findByText(/This is your own account/)).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Suspend account' })).toBeNull();
  });

  it('sends no request for an ID that is not one', () => {
    signInAsModerator();
    renderAt('/admin/users?userId=..%2F..%2Fadmin');

    expect(screen.getByText(/Enter an account ID/)).toBeDefined();
    expect(screen.queryByRole('status')).toBeNull();
  });
});

describe('audit log', () => {
  it('shows each row in words with its metadata and a short hash', async () => {
    server.use(
      apiGet('/admin/audit', () =>
        HttpResponse.json(auditPage([auditEntry(2, { eventType: 'REPORT_CLOSED', metadata: '{"outcome":"ACTIONED"}' }), auditEntry(1)])),
      ),
    );
    signInAsModerator();
    renderAt('/admin/audit');

    expect(await screen.findByText('#2 Report closed')).toBeDefined();
    expect(screen.getByText('#1 Account suspended')).toBeDefined();
    expect(screen.getByText('ACTIONED')).toBeDefined();
    expect(screen.getAllByText(/by the system/)).toHaveLength(2);
    expect(screen.getByText(/Last checked .*: all 42 rows intact\./)).toBeDefined();
  });

  it('shows a broken chain as an alert naming the first bad row', async () => {
    server.use(apiGet('/admin/audit', () => HttpResponse.json(auditPage([], check({ intact: false, firstBrokenSeq: 7 })))));
    signInAsModerator();
    renderAt('/admin/audit');

    expect((await screen.findByRole('alert')).textContent).toContain('the chain is broken from row #7');
  });

  it('runs a check on demand and shows its result', async () => {
    server.use(
      apiGet('/admin/audit', () => HttpResponse.json(auditPage([], null))),
      apiPost('/admin/audit/integrity-check', () => HttpResponse.json(check({ rowsChecked: 43 }))),
    );
    signInAsModerator();
    renderAt('/admin/audit');

    expect(await screen.findByText('The chain has not been checked yet.')).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Check the chain now' }));

    expect(await screen.findByText(/all 43 rows intact/)).toBeDefined();
  });

  it('filters by an ID, and sends no request for one that is not an ID', async () => {
    const asked: (string | null)[] = [];
    server.use(
      apiGet('/admin/audit', ({ request }) => {
        asked.push(new URL(request.url).searchParams.get('entityId'));
        return HttpResponse.json(auditPage([]));
      }),
    );
    signInAsModerator();
    renderAt('/admin/audit');
    await screen.findByText('Nothing has been recorded yet.');
    const input = screen.getByLabelText(/Only rows about this ID/);

    fireEvent.change(input, { target: { value: 'ravi@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
    expect(screen.getByText(/Enter an ID, like/)).toBeDefined();

    fireEvent.change(input, { target: { value: REPORTED_ID } });
    fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
    expect(await screen.findByText('No rows about this ID.')).toBeDefined();
    await waitFor(() => {
      expect(asked).toEqual([null, REPORTED_ID]);
    });
  });
});

describe('data requests', () => {
  it('lists requests by status only', async () => {
    server.use(
      apiGet('/admin/data-requests', () =>
        HttpResponse.json(page([dataRequest(), dataRequest({ id: 'd-2', type: 'DELETION', status: 'FAILED', completedAt: null, expiresAt: null, downloadable: false })])),
      ),
    );
    signInAsModerator();
    renderAt('/admin/data-requests');

    expect(await screen.findByText('Done')).toBeDefined();
    expect(screen.getByText(/downloadable until/)).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
  });

  it('says plainly when there are none', async () => {
    server.use(apiGet('/admin/data-requests', () => HttpResponse.json(page([]))));
    signInAsModerator();
    renderAt('/admin/data-requests');

    expect(await screen.findByText('No one has asked for an export or a deletion yet.')).toBeDefined();
  });
});
