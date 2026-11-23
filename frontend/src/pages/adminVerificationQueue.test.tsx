// @vitest-environment jsdom
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { API_BASE_URL, apiGet, apiPost, createApiServer, problem } from '../test/apiServer';
import { heading, installSessionHandling, renderAt, signIn } from '../test/renderApp';
import { PHOTO_PATH, SELFIE_PATH } from '../test/verificationFixtures';

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

function item(id: string, fullName: string, selfie = true) {
  return {
    verificationId: id,
    userId: `user-${id}`,
    fullName,
    status: 'PENDING',
    similarityScore: 0.734,
    submittedAt: '2026-11-22T09:00:00Z',
    purgeDueAt: '2026-11-29T09:00:00Z',
    selfie: selfie ? { path: SELFIE_PATH, expiresAt: '2026-11-23T10:05:00Z' } : null,
    profilePhoto: { path: PHOTO_PATH, expiresAt: '2026-11-23T10:05:00Z' },
  };
}

function page(items: ReturnType<typeof item>[], pageNumber = 0, totalPages = 1) {
  return { items, page: pageNumber, size: 20, totalItems: items.length, totalPages };
}

async function openQueue(items: ReturnType<typeof item>[]) {
  server.use(apiGet('/admin/verifications', () => HttpResponse.json(page(items))));
  signIn({ role: 'ADMIN', amr: ['pwd', 'otp'] });
  renderAt('/admin/verifications');
  await screen.findByRole('heading', { level: 1, name: 'Selfie review queue' });
}

describe('review queue guard', () => {
  it('is closed to a user who is not an admin', () => {
    signIn();
    renderAt('/admin/verifications');

    expect(heading()).toBe('Not available');
    expect(screen.getByText('This area is for TripMates moderators.')).toBeDefined();
  });

  it('needs an admin session that passed a second factor', () => {
    signIn({ role: 'ADMIN', amr: ['pwd'] });
    renderAt('/admin/verifications');

    expect(screen.getByText(/Admin tools need a sign-in with your authenticator app/)).toBeDefined();
  });
});

describe('review queue', () => {
  it('shows each selfie beside its profile photo through the signed links', async () => {
    await openQueue([item('v-1', 'Asha Verma')]);

    const card = (await screen.findByRole('heading', { level: 2, name: 'Asha Verma' })).closest('li');
    if (card === null) {
      throw new Error('No card');
    }
    expect(within(card).getByRole('img', { name: 'Selfie submitted by Asha Verma' }).getAttribute('src')).toBe(
      `${API_BASE_URL}${SELFIE_PATH}`,
    );
    expect(within(card).getByRole('img', { name: 'Profile photo of Asha Verma' }).getAttribute('src')).toBe(
      `${API_BASE_URL}${PHOTO_PATH}`,
    );
    expect(card.textContent).toContain('similarity 73%');
  });

  it('says when a selfie has already been purged', async () => {
    await openQueue([item('v-1', 'Asha Verma', false)]);

    expect(await screen.findByText('Not available.')).toBeDefined();
  });

  it('says plainly when nothing is waiting', async () => {
    await openQueue([]);

    expect(await screen.findByText('No selfies are waiting for review.')).toBeDefined();
  });

  it.each([
    ['APPROVE', 'Approve', 'Approved.'],
    ['REJECT', 'Reject', 'Rejected.'],
  ])('records a %s decision', async (decision, button, shown) => {
    let body: unknown = null;
    let decidedId = '';
    server.use(
      apiPost('/admin/verifications/:id/decision', async ({ request, params }) => {
        body = await request.json();
        decidedId = String(params['id']);
        return HttpResponse.json({ ...item('v-1', 'Asha Verma'), status: decision === 'APPROVE' ? 'APPROVED' : 'REJECTED' });
      }),
    );
    await openQueue([item('v-1', 'Asha Verma')]);

    fireEvent.click(await screen.findByRole('button', { name: `${button} the selfie of Asha Verma` }));

    expect(await screen.findByText(shown)).toBeDefined();
    expect(body).toEqual({ decision });
    expect(decidedId).toBe('v-1');
    expect(screen.queryByRole('button', { name: 'Approve the selfie of Asha Verma' })).toBeNull();
  });

  it.each([
    ['verification_already_decided', 409, 'This verification has already been decided.'],
    ['cannot_review_own_verification', 403, 'You cannot review your own verification.'],
  ])('shows %s on the card and keeps the buttons', async (code, status, detail) => {
    server.use(apiPost('/admin/verifications/:id/decision', () => problem(status, code, detail)));
    await openQueue([item('v-1', 'Asha Verma')]);

    fireEvent.click(await screen.findByRole('button', { name: 'Approve the selfie of Asha Verma' }));

    expect((await screen.findByRole('alert')).textContent).toContain(detail);
    expect(screen.getByRole('button', { name: 'Approve the selfie of Asha Verma' })).toBeDefined();
  });

  it('pages through the queue', async () => {
    const asked: string[] = [];
    server.use(
      apiGet('/admin/verifications', ({ request }) => {
        const pageNumber = Number(new URL(request.url).searchParams.get('page'));
        asked.push(String(pageNumber));
        return HttpResponse.json(
          page([item(`v-${String(pageNumber)}`, pageNumber === 0 ? 'Asha Verma' : 'Ravi Kumar')], pageNumber, 2),
        );
      }),
    );
    signIn({ role: 'ADMIN', amr: ['pwd', 'otp'] });
    renderAt('/admin/verifications');

    expect(await screen.findByText('Page 1 of 2')).toBeDefined();
    expect(screen.getByRole('button', { name: 'Previous' })).toHaveProperty('disabled', true);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Ravi Kumar')).toBeDefined();
    expect(screen.getByRole('button', { name: 'Next' })).toHaveProperty('disabled', true);
    await waitFor(() => {
      expect(asked).toEqual(['0', '1']);
    });
  });

  it('shows a failure to load with its reference and retries', async () => {
    let attempts = 0;
    server.use(
      apiGet('/admin/verifications', () => {
        attempts++;
        return attempts === 1
          ? problem(403, 'forbidden', 'Forbidden')
          : HttpResponse.json(page([]));
      }),
    );
    signIn({ role: 'ADMIN', amr: ['pwd', 'otp'] });
    renderAt('/admin/verifications');

    expect((await screen.findByRole('alert')).textContent).toContain('You do not have permission to do that.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('No selfies are waiting for review.')).toBeDefined();
  });
});
