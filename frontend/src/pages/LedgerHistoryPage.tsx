import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router';
import { settlementApi, type HistoryItem } from '../api/settlementApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { Pager } from '../components/Pager';
import { useApiResource } from '../hooks/useApiResource';
import { formatPaise } from '../settlement/money';
import { isUuid } from '../trips/ids';

const DATE = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });

/**
 * Every transaction on a trip's ledger, newest first: expenses, their
 * corrections and confirmed payments. Nothing in it is ever edited or
 * removed. It shows obligations settled between travellers, not a wallet.
 */
export function LedgerHistoryPage() {
  const { tripId = '' } = useParams();
  const valid = isUuid(tripId);
  const [page, setPage] = useState(0);
  const load = useCallback(
    () => (valid ? settlementApi.history(tripId, page) : Promise.reject(new Error('Not a trip.'))),
    [tripId, valid, page],
  );
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-3xl">
      <h1 className="text-2xl font-bold text-teal-800">Ledger history</h1>
      <p className="mt-2 text-sm text-slate-600">
        What was spent and settled on this trip. Corrections appear as new lines; nothing is ever changed or deleted.
      </p>
      {valid && (
        <Link to={`/trips/${tripId}/settle-up`} className="mt-2 inline-block text-sm font-medium text-teal-700 underline">
          Back to settle-up
        </Link>
      )}
      <div className="mt-6">
        {!valid || (state.kind === 'failed' && state.failure.status === 404) ? (
          <p className="text-slate-700">This history isn&apos;t available.</p>
        ) : state.kind === 'loading' ? (
          <p role="status" className="text-slate-600">
            Loading…
          </p>
        ) : state.kind === 'failed' ? (
          <div className="space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        ) : state.data.items.length === 0 ? (
          <p className="text-slate-600">Nothing has been recorded yet.</p>
        ) : (
          <>
            <ol aria-label="Ledger history" className="divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
              {state.data.items.map((item) => (
                <li key={item.id} className="px-4 py-3 text-sm">
                  <p className="font-medium text-slate-900">{describe(item)}</p>
                  <p className="text-slate-600">
                    {DATE.format(new Date(item.createdAt))} · {effect(item.myEffectPaise)}
                  </p>
                </li>
              ))}
            </ol>
            <Pager label="History pages" page={state.data.page} totalPages={state.data.totalPages} onChange={setPage} />
          </>
        )}
      </div>
    </section>
  );
}

function describe(item: HistoryItem): string {
  const amount = formatPaise(item.amountPaise);
  switch (item.kind) {
    case 'EXPENSE':
      return `${item.description ?? 'Expense'}: ${amount}, paid by ${item.paidBy?.firstName ?? 'someone'}`;
    case 'EXPENSE_REVERSAL':
      return `Correction: an expense of ${amount} reversed`;
    case 'SETTLEMENT':
      return `${item.from?.firstName ?? 'Someone'} paid ${item.to?.firstName ?? 'someone'} ${amount}, confirmed`;
  }
}

function effect(paise: number): string {
  if (paise > 0) {
    return `others owe you ${formatPaise(paise)} more`;
  }
  if (paise < 0) {
    return `you owe ${formatPaise(-paise)} more`;
  }
  return 'no change for you';
}
