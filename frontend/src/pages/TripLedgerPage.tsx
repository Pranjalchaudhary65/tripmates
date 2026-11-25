import { useCallback, useState, type SubmitEvent } from 'react';
import { Link, useParams } from 'react-router';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { settlementApi, type Expense, type Ledger, type Member, type Settlement } from '../api/settlementApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { FormField, inputClassName } from '../components/FormField';
import { SubmitButton } from '../components/SubmitButton';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { equalShares, formatPaise, rupeesToPaise } from '../settlement/money';
import { isUuid } from '../trips/ids';
import { validateExpense, type ExpenseField } from '../validation/settlementRules';

const STATUS_LABELS: Record<Settlement['status'], string> = {
  PROPOSED: 'To pay',
  MARKED_PAID: 'Sent, waiting for confirmation',
  CONFIRMED: 'Paid and confirmed',
  DISPUTED: 'Disputed, a moderator will look at it',
};

/**
 * A locked trip's settle-up screen: who is on the trip, what each person
 * owes or is owed, the fewest payments that settle it, and the expenses
 * behind them. TripMates never holds the money: payments go person to
 * person over UPI, and only the person paid confirming it changes the
 * balances.
 */
export function TripLedgerPage() {
  const { tripId = '' } = useParams();
  const valid = isUuid(tripId);
  const load = useCallback(
    () => (valid ? settlementApi.ledger(tripId) : Promise.reject(new Error('Not a trip.'))),
    [tripId, valid],
  );
  const { state, reload } = useApiResource(load);

  if (!valid) {
    return <Unavailable />;
  }
  if (state.kind === 'loading') {
    return (
      <p role="status" className="text-slate-600">
        Loading…
      </p>
    );
  }
  if (state.kind === 'failed') {
    if (state.failure.status === 404) {
      return <Unavailable />;
    }
    return (
      <div className="space-y-3">
        <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
        <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
          Try again
        </button>
      </div>
    );
  }
  return <LedgerView initial={state.data} />;
}

function Unavailable() {
  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold text-teal-800">Settle-up not available</h1>
      <p className="mt-2 text-slate-600">
        This trip doesn&apos;t exist, or you aren&apos;t travelling on it. A trip&apos;s settle-up opens to its host and the
        companions who met them in person.
      </p>
      <Link to="/trips" className="mt-4 inline-block font-medium text-teal-700 underline">
        Back to trips
      </Link>
    </section>
  );
}

/** Every action answers with the whole ledger, which replaces what is shown. */
function LedgerView({ initial }: { initial: Ledger }) {
  const [ledger, onChange] = useState(initial);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();
  const me = ledger.members.find((member) => member.me) ?? null;

  const act = useCallback(
    (action: () => Promise<Ledger>) => {
      setFailure(null);
      void run(async () => {
        try {
          onChange(await action());
        } catch (error) {
          setFailure(toApiFailure(error));
        }
      });
    },
    [onChange, run],
  );

  return (
    <section className="max-w-3xl space-y-8">
      <header>
        <h1 className="text-2xl font-bold text-teal-800">Settle up: {ledger.trip.title}</h1>
        <p className="mt-1 text-sm text-slate-600">
          {ledger.trip.destinationName} · {ledger.trip.startDate} to {ledger.trip.endDate}
        </p>
      </header>

      <TripLockPanel ledger={ledger} />

      {failure !== null && <ErrorNotice message={failure.message} correlationId={failure.correlationId} />}

      {ledger.locked && (
        <>
          <Balances ledger={ledger} />
          <Payments ledger={ledger} act={act} pending={pending} />
          {ledger.open && me !== null && <AddExpense ledger={ledger} me={me} onAdded={onChange} />}
          <Expenses expenses={ledger.recentExpenses} act={act} pending={pending} />
          <nav aria-label="More about this ledger" className="flex flex-wrap gap-4 text-sm">
            <Link to={`/trips/${ledger.trip.id}/settle-up/history`} className="font-medium text-teal-700 underline">
              Full history
            </Link>
            <Link to="/account/upi-id" className="font-medium text-teal-700 underline">
              Your UPI ID
            </Link>
            <Link to={`/trips/${ledger.trip.id}/live`} className="font-medium text-teal-700 underline">
              Live trip and SOS
            </Link>
          </nav>
        </>
      )}
    </section>
  );
}

/** The trip lock as the meet-up decided it. There is no deposit step: commitment is the trust score. */
function TripLockPanel({ ledger }: { ledger: Ledger }) {
  if (!ledger.locked) {
    return (
      <div role="region" aria-label="Trip lock" className="rounded-md border border-slate-200 bg-white px-4 py-4">
        <h2 className="text-lg font-semibold text-slate-900">Not locked yet</h2>
        <p className="mt-1 text-sm text-slate-700">
          A trip locks for each companion once you have met in a public place and both felt comfortable. Shared expenses
          open then. Nobody pays a deposit, to TripMates or anyone else.
        </p>
      </div>
    );
  }
  return (
    <div role="region" aria-label="Trip lock" className="rounded-md border border-teal-200 bg-teal-50 px-4 py-4">
      <h2 className="text-lg font-semibold text-teal-900">Trip locked</h2>
      <p className="mt-1 text-sm text-teal-900">
        Everyone below met in person before the trip locked. No deposit was taken: TripMates never holds your money.
      </p>
      <ul className="mt-3 flex flex-wrap gap-2 text-sm">
        {ledger.members.map((member) => (
          <li key={member.userId} className="rounded-full bg-white px-3 py-1 text-slate-800">
            {member.me ? 'You' : member.firstName}
            {member.role === 'HOST' ? ' (host)' : ''}
            {member.onTrip ? '' : ' · stepped back'}
          </li>
        ))}
      </ul>
    </div>
  );
}

function balanceText(member: Member): string {
  if (member.netPaise > 0) {
    return `${member.me ? 'You are' : `${member.firstName} is`} owed ${formatPaise(member.netPaise)}`;
  }
  if (member.netPaise < 0) {
    return `${member.me ? 'You owe' : `${member.firstName} owes`} ${formatPaise(-member.netPaise)}`;
  }
  return `${member.me ? 'You are' : `${member.firstName} is`} even`;
}

function Balances({ ledger }: { ledger: Ledger }) {
  return (
    <div>
      <h2 className="text-lg font-semibold text-slate-900">Balances</h2>
      <p className="mt-1 text-sm text-slate-600">
        {formatPaise(ledger.totalSpentPaise)} spent together so far. These are what you owe each other, not money held
        anywhere.
      </p>
      <ul aria-label="Balances" className="mt-3 divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
        {ledger.members.map((member) => (
          <li key={member.userId} className="px-4 py-2 text-sm">
            {balanceText(member)}
          </li>
        ))}
      </ul>
    </div>
  );
}

type Act = (action: () => Promise<Ledger>) => void;

function Payments({ ledger, act, pending }: { ledger: Ledger; act: Act; pending: boolean }) {
  const open = ledger.settlements.filter((settlement) => settlement.status !== 'CONFIRMED');
  const done = ledger.settlements.filter((settlement) => settlement.status === 'CONFIRMED');
  return (
    <div>
      <h2 className="text-lg font-semibold text-slate-900">Who pays whom</h2>
      {ledger.settledUp ? (
        <p className="mt-2 rounded-md bg-teal-50 px-4 py-3 text-sm text-teal-900">Everyone is settled up.</p>
      ) : open.length === 0 ? (
        <p className="mt-2 text-sm text-slate-600">Nothing to pay right now.</p>
      ) : (
        <>
          <p className="mt-1 text-sm text-slate-600">
            The fewest payments that settle everything. Pay directly over UPI; the person paid confirms it here.
          </p>
          <ul aria-label="Payments to make" className="mt-3 space-y-3">
            {open.map((settlement) => (
              <PaymentRow key={settlement.id} settlement={settlement} act={act} pending={pending} />
            ))}
          </ul>
        </>
      )}
      {done.length > 0 && (
        <ul aria-label="Confirmed payments" className="mt-4 space-y-1 text-sm text-slate-600">
          {done.map((settlement) => (
            <li key={settlement.id}>
              {name(settlement.from, settlement.myPart === 'PAYER')} paid {name(settlement.to, settlement.myPart === 'PAYEE')}{' '}
              {formatPaise(settlement.amountPaise)} · confirmed
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function name(person: { firstName: string }, me: boolean): string {
  return me ? 'You' : person.firstName;
}

function PaymentRow({ settlement, act, pending }: { settlement: Settlement; act: Act; pending: boolean }) {
  const [disputing, setDisputing] = useState(false);
  const payer = settlement.myPart === 'PAYER';
  const payee = settlement.myPart === 'PAYEE';
  return (
    <li
      aria-label={`${settlement.from.firstName} to ${settlement.to.firstName}`}
      className="rounded-md border border-slate-200 bg-white px-4 py-3"
    >
      <p className="font-medium text-slate-900">
        {name(settlement.from, payer)} {payer ? 'pay' : 'pays'} {name(settlement.to, payee)}{' '}
        {formatPaise(settlement.amountPaise)}
      </p>
      <p className="text-sm text-slate-600">{STATUS_LABELS[settlement.status]}</p>

      {payer && settlement.status === 'PROPOSED' && (
        <div className="mt-3 space-y-2">
          {settlement.payment === null ? (
            <p className="text-sm text-slate-700">
              {settlement.to.firstName} hasn&apos;t added a UPI ID. Ask them for it in your chat, pay them, then mark it
              as sent.
            </p>
          ) : (
            <>
              <a
                href={settlement.payment.upiLink}
                className="inline-block rounded-md bg-teal-700 px-4 py-2 text-sm font-medium text-white hover:bg-teal-800"
              >
                Pay {formatPaise(settlement.amountPaise)} with a UPI app
              </a>
              <p className="text-xs text-slate-600">
                To {settlement.payment.payeeUpiId}. Check the name your UPI app shows before you approve.
              </p>
              {settlement.payment.upiChangedRecently && (
                <p role="note" className="rounded bg-amber-50 px-3 py-2 text-sm text-amber-900">
                  {settlement.to.firstName} changed this UPI ID in the last two days. Confirm it with them in chat before
                  you pay.
                </p>
              )}
            </>
          )}
        </div>
      )}

      <div className="mt-3 flex flex-wrap gap-3">
        {settlement.canMarkPaid && (
          <button
            type="button"
            disabled={pending}
            onClick={() => {
              act(() => settlementApi.markPaid(settlement.id));
            }}
            className="rounded-md border border-teal-700 px-3 py-1.5 text-sm font-medium text-teal-800 disabled:opacity-50"
          >
            I&apos;ve sent it
          </button>
        )}
        {settlement.canConfirm && (
          <button
            type="button"
            disabled={pending}
            onClick={() => {
              act(() => settlementApi.confirm(settlement.id));
            }}
            className="rounded-md bg-teal-700 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            I received {formatPaise(settlement.amountPaise)}
          </button>
        )}
        {settlement.canDispute && !disputing && (
          <button
            type="button"
            onClick={() => {
              setDisputing(true);
            }}
            className="text-sm font-medium text-red-700 underline"
          >
            Something&apos;s wrong
          </button>
        )}
      </div>
      {payee && settlement.status === 'MARKED_PAID' && (
        <p className="mt-2 text-xs text-slate-600">
          Check your bank or UPI app first. Confirm only once the money has arrived.
        </p>
      )}
      {disputing && (
        <div role="group" aria-label="Dispute this payment" className="mt-3 rounded-md bg-red-50 px-3 py-3 text-sm">
          <p className="text-red-900">
            This stops the payment and asks a TripMates moderator to look at it. Use it when money was sent but not
            received, or the amount is wrong.
          </p>
          <div className="mt-2 flex gap-3">
            <button
              type="button"
              disabled={pending}
              onClick={() => {
                setDisputing(false);
                act(() => settlementApi.dispute(settlement.id));
              }}
              className="rounded-md bg-red-700 px-3 py-1.5 font-medium text-white disabled:opacity-50"
            >
              Dispute this payment
            </button>
            <button
              type="button"
              onClick={() => {
                setDisputing(false);
              }}
              className="font-medium text-slate-700 underline"
            >
              Keep it
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

const EXPENSE_FIELDS = ['description', 'amountPaise', 'participants'] as const;

function AddExpense({ ledger, me, onAdded }: { ledger: Ledger; me: Member; onAdded: (ledger: Ledger) => void }) {
  const travellers = ledger.members.filter((member) => member.onTrip);
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [split, setSplit] = useState<'EQUAL' | 'EXACT'>('EQUAL');
  const [chosen, setChosen] = useState<Map<string, string>>(
    () => new Map(travellers.map((member) => [member.userId, ''])),
  );
  /** One ID per expense, reused on every retry of it, so a lost response can never record it twice. */
  const [clientExpenseId, setClientExpenseId] = useState(() => crypto.randomUUID());
  const [localErrors, setLocalErrors] = useState<Partial<Record<ExpenseField, string>>>({});
  const failure = useRequestFailure(EXPENSE_FIELDS);
  const { pending, run } = useSubmission();

  const amountPaise = rupeesToPaise(amount);
  const preview = split === 'EQUAL' && amountPaise !== null ? equalShares(amountPaise, [...chosen.keys()]) : null;

  function edited() {
    setClientExpenseId(crypto.randomUUID());
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const errors = validateExpense({ description, amount, split, participants: chosen }, me.userId);
    setLocalErrors(errors);
    failure.clear();
    if (Object.keys(errors).length > 0 || amountPaise === null) {
      return;
    }
    const participants = [...chosen.entries()].map(([userId, share]) =>
      split === 'EXACT' ? { userId, sharePaise: rupeesToPaise(share) ?? 0 } : { userId },
    );
    void run(async () => {
      try {
        const updated = await settlementApi.addExpense(ledger.trip.id, {
          clientExpenseId,
          description: description.trim(),
          amountPaise,
          split,
          participants,
        });
        setDescription('');
        setAmount('');
        setClientExpenseId(crypto.randomUUID());
        onAdded(updated);
      } catch (error) {
        failure.report(toApiFailure(error));
      }
    });
  }

  const errorFor = (field: ExpenseField) => localErrors[field] ?? failure.fieldErrors[field];

  return (
    <form onSubmit={submit} noValidate aria-label="Add an expense" className="space-y-4 rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="text-lg font-semibold text-slate-900">Add something you paid for</h2>
      <FormField id="expense-description" label="What was it?" error={errorFor('description')}>
        {(control) => (
          <input
            {...control}
            value={description}
            maxLength={120}
            onChange={(event) => {
              setDescription(event.target.value);
              failure.clearField('description');
              edited();
            }}
            className={inputClassName}
          />
        )}
      </FormField>
      <FormField id="expense-amount" label="Amount in rupees" error={errorFor('amountPaise')} hint="Like 1800 or 1800.50">
        {(control) => (
          <input
            {...control}
            value={amount}
            inputMode="decimal"
            onChange={(event) => {
              setAmount(event.target.value);
              failure.clearField('amountPaise');
              edited();
            }}
            className={inputClassName}
          />
        )}
      </FormField>
      <fieldset>
        <legend className="text-sm font-medium text-slate-800">Split</legend>
        <div className="mt-1 flex gap-4 text-sm">
          {(['EQUAL', 'EXACT'] as const).map((value) => (
            <label key={value} className="flex items-center gap-2">
              <input
                type="radio"
                name="split"
                checked={split === value}
                onChange={() => {
                  setSplit(value);
                  edited();
                }}
              />
              {value === 'EQUAL' ? 'Equally' : 'Exact amounts'}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset aria-describedby={errorFor('participants') === undefined ? undefined : 'expense-participants-error'}>
        <legend className="text-sm font-medium text-slate-800">Shared with</legend>
        <ul className="mt-1 space-y-2 text-sm">
          {travellers.map((member) => {
            const included = chosen.has(member.userId);
            const label = member.me ? 'You' : member.firstName;
            return (
              <li key={member.userId} className="flex flex-wrap items-center gap-3">
                <label className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={included}
                    onChange={() => {
                      const next = new Map(chosen);
                      if (included) {
                        next.delete(member.userId);
                      } else {
                        next.set(member.userId, '');
                      }
                      setChosen(next);
                      failure.clearField('participants');
                      edited();
                    }}
                  />
                  {label}
                </label>
                {included && split === 'EXACT' && (
                  <input
                    aria-label={member.me ? 'Your share in rupees' : `${member.firstName}'s share in rupees`}
                    value={chosen.get(member.userId) ?? ''}
                    inputMode="decimal"
                    onChange={(event) => {
                      setChosen(new Map(chosen).set(member.userId, event.target.value));
                      edited();
                    }}
                    className="w-32 rounded-md border border-slate-300 px-2 py-1"
                  />
                )}
                {included && preview !== null && (
                  <span className="text-slate-600">{formatPaise(preview.get(member.userId) ?? 0)}</span>
                )}
              </li>
            );
          })}
        </ul>
        {errorFor('participants') !== undefined && (
          <p id="expense-participants-error" className="mt-1 text-sm text-red-700">
            {errorFor('participants')}
          </p>
        )}
      </fieldset>
      {failure.notice !== null && (
        <ErrorNotice
          message={failure.notice.message}
          correlationId={failure.notice.correlationId}
          retrySecondsLeft={failure.noticeRetrySeconds}
        />
      )}
      <SubmitButton label="Add expense" pendingLabel="Adding…" pending={pending} />
    </form>
  );
}

function Expenses({ expenses, act, pending }: { expenses: Expense[]; act: Act; pending: boolean }) {
  const [reversing, setReversing] = useState<string | null>(null);
  return (
    <div>
      <h2 className="text-lg font-semibold text-slate-900">Expenses</h2>
      {expenses.length === 0 ? (
        <p className="mt-2 text-sm text-slate-600">No expenses yet.</p>
      ) : (
        <ul aria-label="Expenses" className="mt-3 divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
          {expenses.map((expense) => (
            <li key={expense.id} className="px-4 py-3 text-sm">
              <p className={`font-medium ${expense.reversed ? 'text-slate-500 line-through' : 'text-slate-900'}`}>
                {expense.description} · {formatPaise(expense.amountPaise)}
              </p>
              <p className="text-slate-600">
                Paid by {expense.paidBy.firstName}, shared by{' '}
                {expense.shares.map((share) => `${share.person.firstName} ${formatPaise(share.sharePaise)}`).join(', ')}
                {expense.reversed ? ' · reversed' : ''}
              </p>
              {expense.canReverse && reversing !== expense.id && (
                <button
                  type="button"
                  onClick={() => {
                    setReversing(expense.id);
                  }}
                  className="mt-1 text-sm font-medium text-red-700 underline"
                >
                  Reverse
                </button>
              )}
              {reversing === expense.id && (
                <div role="group" aria-label="Reverse this expense" className="mt-2 flex flex-wrap items-center gap-3">
                  <span className="text-slate-700">Records its exact opposite. It stays in the history.</span>
                  <button
                    type="button"
                    disabled={pending}
                    onClick={() => {
                      setReversing(null);
                      act(() => settlementApi.reverse(expense.id));
                    }}
                    className="rounded-md bg-red-700 px-3 py-1 font-medium text-white disabled:opacity-50"
                  >
                    Reverse expense
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setReversing(null);
                    }}
                    className="font-medium text-slate-700 underline"
                  >
                    Keep it
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
