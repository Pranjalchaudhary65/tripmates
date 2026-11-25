import { useCallback } from 'react';
import { Link } from 'react-router';
import { trustApi } from '../api/trustApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { formatDay, stars } from '../trust/trustLabels';

/** Trips waiting for the user's reviews, and the reviews about them that are visible. */
export function ReviewsPage() {
  const load = useCallback(() => trustApi.myReviews(), []);
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Reviews</h1>
      <div className="mt-6">
        {state.kind === 'loading' && (
          <p role="status" className="text-slate-600">
            Loading…
          </p>
        )}
        {state.kind === 'failed' && (
          <div className="space-y-3">
            <ErrorNotice message={state.failure.message} correlationId={state.failure.correlationId} />
            <button type="button" onClick={reload} className="font-medium text-teal-700 underline">
              Try again
            </button>
          </div>
        )}
        {state.kind === 'ready' && (
          <div className="space-y-8">
            <div>
              <h2 className="text-lg font-semibold text-slate-900">To write</h2>
              {state.data.toReview.length === 0 ? (
                <p className="mt-1 text-sm text-slate-600">Nothing waiting. Reviews open the day after a trip ends.</p>
              ) : (
                <ul className="mt-2 space-y-2">
                  {state.data.toReview.map((item) => (
                    <li key={item.trip.id} className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm">
                      <Link to={`/trips/${item.trip.id}/reviews`} className="font-medium text-teal-700 underline">
                        {item.trip.title}
                      </Link>
                      <p className="text-slate-600">
                        {`${String(item.waiting)} ${item.waiting === 1 ? 'person' : 'people'} to review · closes ${formatDay(item.closesAt)}`}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <h2 className="text-lg font-semibold text-slate-900">About you</h2>
              {state.data.received.length === 0 ? (
                <p className="mt-1 text-sm text-slate-600">
                  No reviews yet. A review shows here once you have both written one, or reviews have closed.
                </p>
              ) : (
                <ul className="mt-2 space-y-2">
                  {state.data.received.map((review) => (
                    <li
                      key={`${review.tripId}-${review.visibleAt}-${review.reviewerFirstName}`}
                      className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm"
                    >
                      <p aria-label={`${String(review.rating)} out of 5 stars`} className="text-amber-600">
                        {stars(review.rating)}
                      </p>
                      {review.comment !== null && <p className="mt-1 text-slate-800">{review.comment}</p>}
                      <p className="mt-1 text-xs text-slate-600">
                        {review.reviewerFirstName} · {review.tripTitle} · {formatDay(review.visibleAt)}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <p className="text-sm">
              <Link to="/account/trust-score" className="font-medium text-teal-700 underline">
                How reviews count in your trust score
              </Link>
            </p>
          </div>
        )}
      </div>
    </section>
  );
}
