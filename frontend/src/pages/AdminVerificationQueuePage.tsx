import { useCallback, useState } from 'react';
import { toApiFailure, type ApiFailure } from '../api/problem';
import { verificationApi, type ReviewDecision, type ReviewItem } from '../api/verificationApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { useSubmission } from '../hooks/useSubmission';

const WHEN = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

/**
 * Selfies the liveness check could not decide on its own, oldest first, each
 * beside the profile photo it was compared against. Images come through
 * links that expire in minutes; reloading the page fetches fresh ones. A
 * decision is approve or reject only: nothing a reviewer types reaches the
 * user. Reports have their own queue.
 */
export function AdminVerificationQueuePage() {
  const [page, setPage] = useState(0);
  const load = useCallback(() => verificationApi.reviewQueue(page), [page]);
  const { state, reload } = useApiResource(load);
  const [decided, setDecided] = useState<Record<string, ReviewDecision>>({});

  return (
    <section className="max-w-4xl">
      <h1 className="text-2xl font-bold text-teal-800">Selfie review queue</h1>
      <p className="mt-2 text-sm text-slate-600">
        Approve only when the selfie and the profile photo clearly show the same person. Each selfie is deleted 7
        days after it was taken, reviewed or not.
      </p>

      {state.kind === 'loading' && (
        <p role="status" className="mt-6 text-slate-600">
          Loading the queue…
        </p>
      )}
      {state.kind === 'failed' && (
        <div className="mt-6 space-y-3">
          <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
          <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
            Try again
          </button>
        </div>
      )}
      {state.kind === 'ready' && state.data.items.length === 0 && (
        <p className="mt-6 text-slate-600">No selfies are waiting for review.</p>
      )}
      {state.kind === 'ready' && state.data.items.length > 0 && (
        <>
          <ul className="mt-6 space-y-4">
            {state.data.items.map((item) => (
              <ReviewCard
                key={item.verificationId}
                item={item}
                decided={decided[item.verificationId] ?? null}
                onDecided={(decision) => {
                  setDecided((current) => ({ ...current, [item.verificationId]: decision }));
                }}
              />
            ))}
          </ul>
          <nav aria-label="Queue pages" className="mt-6 flex items-center gap-4 text-sm">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => {
                setPage((current) => current - 1);
              }}
              className="font-medium text-teal-700 underline disabled:text-slate-400 disabled:no-underline"
            >
              Previous
            </button>
            <span>
              Page {state.data.page + 1} of {Math.max(state.data.totalPages, 1)}
            </span>
            <button
              type="button"
              disabled={state.data.page + 1 >= state.data.totalPages}
              onClick={() => {
                setPage((current) => current + 1);
              }}
              className="font-medium text-teal-700 underline disabled:text-slate-400 disabled:no-underline"
            >
              Next
            </button>
          </nav>
        </>
      )}
    </section>
  );
}

function ReviewCard({
  item,
  decided,
  onDecided,
}: {
  item: ReviewItem;
  decided: ReviewDecision | null;
  onDecided: (decision: ReviewDecision) => void;
}) {
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const { pending, run } = useSubmission();

  function decide(decision: ReviewDecision) {
    setFailure(null);
    void run(async () => {
      try {
        await verificationApi.decide(item.verificationId, decision);
        onDecided(decision);
      } catch (error) {
        setFailure(toApiFailure(error));
      }
    });
  }

  return (
    <li className="rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 className="font-semibold text-slate-900">{item.fullName}</h2>
      <p className="text-xs text-slate-500">
        Submitted {formatWhen(item.submittedAt)} · deleted {formatWhen(item.purgeDueAt)}
        {item.similarityScore !== null && ` · similarity ${String(Math.round(item.similarityScore * 100))}%`}
      </p>
      <div className="mt-3 grid grid-cols-2 gap-4">
        <Photo label="Selfie" alt={`Selfie submitted by ${item.fullName}`} src={item.selfie?.src ?? null} />
        <Photo
          label="Profile photo"
          alt={`Profile photo of ${item.fullName}`}
          src={item.profilePhoto?.src ?? null}
        />
      </div>
      {failure !== null && (
        <div className="mt-3">
          <ErrorNotice message={failure.message} correlationId={failure.correlationId} />
        </div>
      )}
      {decided === null ? (
        <div className="mt-3 flex gap-3">
          <button
            type="button"
            onClick={() => {
              decide('APPROVE');
            }}
            disabled={pending}
            aria-label={`Approve the selfie of ${item.fullName}`}
            className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white hover:bg-teal-800 disabled:bg-slate-400"
          >
            Approve
          </button>
          <button
            type="button"
            onClick={() => {
              decide('REJECT');
            }}
            disabled={pending}
            aria-label={`Reject the selfie of ${item.fullName}`}
            className="rounded-md border border-red-300 px-4 py-2 font-semibold text-red-800 hover:bg-red-50 disabled:text-slate-400"
          >
            Reject
          </button>
        </div>
      ) : (
        <p role="status" className="mt-3 font-medium text-slate-800">
          {decided === 'APPROVE' ? 'Approved.' : 'Rejected.'}
        </p>
      )}
    </li>
  );
}

function Photo({ label, alt, src }: { label: string; alt: string; src: string | null }) {
  return (
    <figure>
      <figcaption className="text-xs font-medium text-slate-600">{label}</figcaption>
      {src === null ? (
        <p className="mt-1 text-sm text-slate-500">Not available.</p>
      ) : (
        <img src={src} alt={alt} className="mt-1 aspect-square w-full rounded-md object-cover" />
      )}
    </figure>
  );
}

function formatWhen(iso: string): string {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? 'at an unknown time' : WHEN.format(at);
}
