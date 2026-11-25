// @vitest-environment jsdom
import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { REFRESH_PATH } from '../api/authRefresh';
import { useAuthStore } from '../stores/authStore';
import { apiDelete, apiGet, apiPost, apiPut, createApiServer, problem } from '../test/apiServer';
import { installSessionHandling, renderAt, SIGNED_IN_USER_ID, signIn } from '../test/renderApp';
import {
  EXPENSE_ID,
  expense,
  HOST_ID,
  historyPage,
  ledger,
  OTHER_ID,
  payment,
  SETTLEMENT_ID,
  settlement,
  TRIP_ID,
} from '../test/settlementFixtures';

const server = createApiServer();
const LEDGER_PATH = `/trips/${TRIP_ID}/ledger`;

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

async function openLedger(body: Record<string, unknown>) {
  server.use(apiGet(LEDGER_PATH, () => HttpResponse.json(body)));
  signIn();
  renderAt(`/trips/${TRIP_ID}/settle-up`);
  await screen.findByRole('heading', { level: 1, name: 'Settle up: Monsoon weekend in Gokarna' });
}

describe('settle-up screen', () => {
  it('shows the meet-up lock, balances and the payer’s UPI button', async () => {
    await openLedger(ledger());

    const lock = screen.getByRole('region', { name: 'Trip lock' });
    expect(within(lock).getByText('Trip locked')).toBeDefined();
    expect(within(lock).getByText(/No deposit was taken/)).toBeDefined();
    const balances = screen.getByRole('list', { name: 'Balances' });
    expect(within(balances).getByText('Asha is owed ₹2,400.00')).toBeDefined();
    expect(within(balances).getByText('You owe ₹1,200.00')).toBeDefined();
    const pay = screen.getByRole('link', { name: 'Pay ₹1,200.00 with a UPI app' });
    expect(pay.getAttribute('href')).toBe(payment().upiLink);
    expect(screen.getAllByRole('link', { name: /with a UPI app/ })).toHaveLength(1);
  });

  it('warns the payer about a UPI ID changed in the last two days', async () => {
    await openLedger(ledger({ settlements: [settlement({ payment: payment({ upiChangedRecently: true }) })] }));

    expect(screen.getByRole('note').textContent).toMatch(/changed this UPI ID in the last two days/);
  });

  it('tells the payer to ask in chat when the payee has no UPI ID', async () => {
    await openLedger(ledger({ settlements: [settlement({ payment: null })] }));

    expect(screen.getByText(/hasn't added a UPI ID. Ask them for it in your chat/)).toBeDefined();
    expect(screen.queryByRole('link', { name: /with a UPI app/ })).toBeNull();
  });

  it('marks a payment as sent, then shows what the server says', async () => {
    let posted = false;
    server.use(
      apiPost(`/settlements/${SETTLEMENT_ID}/mark-paid`, () => {
        posted = true;
        return HttpResponse.json(
          ledger({
            settlements: [
              settlement({ status: 'MARKED_PAID', markedPaidAt: '2026-12-15T11:00:00Z', canMarkPaid: false }),
            ],
          }),
        );
      }),
    );
    await openLedger(ledger());

    fireEvent.click(screen.getByRole('button', { name: "I've sent it" }));

    expect(await screen.findByText('Sent, waiting for confirmation')).toBeDefined();
    expect(posted).toBe(true);
    expect(screen.queryByRole('button', { name: "I've sent it" })).toBeNull();
  });

  it('lets the payee confirm only once it is marked as sent, and shows a refusal in words', async () => {
    server.use(
      apiPost(`/settlements/${SETTLEMENT_ID}/confirm`, () =>
        problem(409, 'cannot_confirm_own_payment', 'The person you paid confirms it. You cannot confirm your own payment.'),
      ),
    );
    await openLedger(
      ledger({
        settlements: [
          settlement({
            from: { userId: OTHER_ID, firstName: 'Meera' },
            to: { userId: SIGNED_IN_USER_ID, firstName: 'Ravi' },
            status: 'MARKED_PAID',
            markedPaidAt: '2026-12-15T11:00:00Z',
            myPart: 'PAYEE',
            canMarkPaid: false,
            canConfirm: true,
            payment: null,
          }),
        ],
      }),
    );

    expect(screen.getByText(/Confirm only once the money has arrived/)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'I received ₹1,200.00' }));

    expect((await screen.findByRole('alert')).textContent).toMatch(/cannot confirm your own payment/);
  });

  it('asks before disputing, and disputes only on the second click', async () => {
    let disputes = 0;
    server.use(
      apiPost(`/settlements/${SETTLEMENT_ID}/dispute`, () => {
        disputes += 1;
        return HttpResponse.json(
          ledger({ settlements: [settlement({ status: 'DISPUTED', canMarkPaid: false, canDispute: false, payment: null })] }),
        );
      }),
    );
    await openLedger(ledger());

    fireEvent.click(screen.getByRole('button', { name: "Something's wrong" }));
    expect(disputes).toBe(0);
    fireEvent.click(screen.getByRole('button', { name: 'Dispute this payment' }));

    expect(await screen.findByText('Disputed, a moderator will look at it')).toBeDefined();
    expect(disputes).toBe(1);
  });

  it('adds an equal expense in whole paise, with one ID reused on a retry', async () => {
    const sent: Record<string, unknown>[] = [];
    let first = true;
    server.use(
      apiPost(`/trips/${TRIP_ID}/expenses`, async ({ request }) => {
        sent.push((await request.json()) as Record<string, unknown>);
        if (first) {
          first = false;
          return problem(503, 'temporarily_unavailable', 'Try again shortly.');
        }
        return HttpResponse.json(ledger({ recentExpenses: [expense(), expense({ id: OTHER_ID, description: 'Taxi' })] }), {
          status: 201,
        });
      }),
    );
    await openLedger(ledger());
    const form = screen.getByRole('form', { name: 'Add an expense' });

    fireEvent.change(within(form).getByLabelText('What was it?'), { target: { value: 'Taxi' } });
    fireEvent.change(within(form).getByLabelText('Amount in rupees'), { target: { value: '0.29' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add expense' }));
    await screen.findByRole('alert');
    fireEvent.click(within(form).getByRole('button', { name: 'Add expense' }));

    expect(await screen.findByText(/^Taxi ·/)).toBeDefined();
    expect(sent).toHaveLength(2);
    expect(sent[0]).toMatchObject({ description: 'Taxi', amountPaise: 29, split: 'EQUAL' });
    expect((sent[0]?.['participants'] as unknown[]).length).toBe(3);
    expect(sent[1]?.['clientExpenseId']).toBe(sent[0]?.['clientExpenseId']);
  });

  it('catches a split that does not add up before sending anything', async () => {
    let posts = 0;
    server.use(
      apiPost(`/trips/${TRIP_ID}/expenses`, () => {
        posts += 1;
        return HttpResponse.json(ledger(), { status: 201 });
      }),
    );
    await openLedger(ledger());
    const form = screen.getByRole('form', { name: 'Add an expense' });

    fireEvent.change(within(form).getByLabelText('What was it?'), { target: { value: 'Dinner' } });
    fireEvent.change(within(form).getByLabelText('Amount in rupees'), { target: { value: '900' } });
    fireEvent.click(within(form).getByRole('radio', { name: 'Exact amounts' }));
    fireEvent.change(within(form).getByLabelText('Your share in rupees'), { target: { value: '300' } });
    fireEvent.change(within(form).getByLabelText("Asha's share in rupees"), { target: { value: '300' } });
    fireEvent.change(within(form).getByLabelText("Meera's share in rupees"), { target: { value: '200' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add expense' }));

    expect(await within(form).findByText('The shares must add up to the amount exactly.')).toBeDefined();
    expect(posts).toBe(0);
  });

  it('reverses an expense only after asking', async () => {
    let reversed = false;
    server.use(
      apiPost(`/expenses/${EXPENSE_ID}/reverse`, () => {
        reversed = true;
        return HttpResponse.json(ledger({ recentExpenses: [expense({ reversed: true })] }));
      }),
    );
    await openLedger(ledger({ recentExpenses: [expense({ canReverse: true })] }));

    fireEvent.click(screen.getByRole('button', { name: 'Reverse' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reverse expense' }));

    expect(await screen.findByText(/· reversed/)).toBeDefined();
    expect(reversed).toBe(true);
  });

  it('shows a trip that is not locked yet without any expense form', async () => {
    await openLedger(
      ledger({
        locked: false,
        open: false,
        members: [{ userId: HOST_ID, firstName: 'Asha', role: 'HOST', onTrip: true, me: false, netPaise: 0 }],
        myNetPaise: 0,
        totalSpentPaise: 0,
        settlements: [],
        recentExpenses: [],
        settledUp: true,
      }),
    );

    expect(screen.getByText('Not locked yet')).toBeDefined();
    expect(screen.queryByRole('form', { name: 'Add an expense' })).toBeNull();
  });

  it('shows the same "not available" for a trip the user is not on and for a bad ID', async () => {
    server.use(apiGet(LEDGER_PATH, () => problem(404, 'not_found', 'The requested resource was not found.')));
    signIn();
    renderAt(`/trips/${TRIP_ID}/settle-up`);
    expect(await screen.findByRole('heading', { name: 'Settle-up not available' })).toBeDefined();

    cleanup();
    renderAt('/trips/not-a-uuid/settle-up');
    expect(await screen.findByRole('heading', { name: 'Settle-up not available' })).toBeDefined();
  });

  it('fails the load rather than render a payment link it does not trust', async () => {
    server.use(
      apiGet(LEDGER_PATH, () =>
        HttpResponse.json(ledger({ settlements: [settlement({ payment: payment({ upiLink: 'javascript:alert(1)' }) })] })),
      ),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}/settle-up`);

    expect(await screen.findByRole('alert')).toBeDefined();
    expect(document.querySelector('a[href^="javascript"]')).toBeNull();
  });
});

describe('ledger history', () => {
  it('lists expenses, corrections and payments with their effect on the user', async () => {
    server.use(
      apiGet(`${LEDGER_PATH}/history`, () =>
        HttpResponse.json(
          historyPage([
            {
              id: SETTLEMENT_ID,
              kind: 'SETTLEMENT',
              createdAt: '2026-12-15T12:00:00Z',
              description: null,
              amountPaise: 120_000,
              paidBy: null,
              from: { userId: SIGNED_IN_USER_ID, firstName: 'Ravi' },
              to: { userId: HOST_ID, firstName: 'Asha' },
              reversesId: null,
              myEffectPaise: 120_000,
            },
            {
              id: EXPENSE_ID,
              kind: 'EXPENSE',
              createdAt: '2026-12-14T09:00:00Z',
              description: 'Homestay',
              amountPaise: 360_000,
              paidBy: { userId: HOST_ID, firstName: 'Asha' },
              from: null,
              to: null,
              reversesId: null,
              myEffectPaise: -120_000,
            },
          ]),
        ),
      ),
    );
    signIn();
    renderAt(`/trips/${TRIP_ID}/settle-up/history`);

    const list = await screen.findByRole('list', { name: 'Ledger history' });
    expect(within(list).getByText('Ravi paid Asha ₹1,200.00, confirmed')).toBeDefined();
    expect(within(list).getByText(/Homestay: ₹3,600.00, paid by Asha/)).toBeDefined();
    expect(within(list).getByText(/you owe ₹1,200.00 more/)).toBeDefined();
  });
});

describe('UPI ID page', () => {
  it('saves a UPI ID lower-cased and checks it before sending', async () => {
    let stored: string | null = null;
    let puts = 0;
    server.use(
      apiGet('/users/me/upi-id', () =>
        HttpResponse.json({ upiId: stored === null ? null : { upiId: stored, updatedAt: '2026-12-15T10:00:00Z' } }),
      ),
      apiPut('/users/me/upi-id', async ({ request }) => {
        puts += 1;
        stored = ((await request.json()) as { upiId: string }).upiId;
        return HttpResponse.json({ upiId: { upiId: stored, updatedAt: '2026-12-15T10:00:00Z' } });
      }),
      apiDelete('/users/me/upi-id', () => new HttpResponse(null, { status: 204 })),
    );
    signIn();
    renderAt('/account/upi-id');

    const input = await screen.findByLabelText('Add a UPI ID');
    fireEvent.change(input, { target: { value: 'asha@okbank&am=1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save UPI ID' }));
    expect(await screen.findByText('Enter a UPI ID like name@bank.')).toBeDefined();
    expect(puts).toBe(0);

    fireEvent.change(input, { target: { value: 'Asha.Rao@OkBank' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save UPI ID' }));

    const current = await screen.findByRole('region', { name: 'Current UPI ID' });
    expect(within(current).getByText('asha.rao@okbank')).toBeDefined();
    expect(stored).toBe('asha.rao@okbank');
  });
});
