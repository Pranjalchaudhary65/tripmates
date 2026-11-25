import { useCallback, useState, type SubmitEvent } from 'react';
import { Link, useParams } from 'react-router';
import { toApiFailure } from '../api/problem';
import { trustApi, type ReviewPerson, type TripReviews } from '../api/trustApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { ReportBlock } from '../components/ReportBlock';
import { useApiResource } from '../hooks/useApiResource';
import { useRequestFailure } from '../hooks/useRequestFailure';
import { useSubmission } from '../hooks/useSubmission';
import { isUuid } from '../trips/ids';
import { formatDay, stars } from '../trust/trustLabels';

const FIELDS = ['rating', 'comment'] as const;
const MAX_COMMENT = 500;

/**
 * Reviews for one trip, double-blind: what the user writes stays hidden from
 * the other person until they review back or reviews close, and theirs is
 * shown only then. Reviews cannot be changed once sent.
 */
export function TripReviewsPage() {
  const { tripId = '' } = useParams();
  if (!isUuid(tripId)) {
    return <Unavailable />;
  }
  return <TripReviewsLoader key={tripId} tripId={tripId} />;
}

function Unavailable() {
  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold text-teal-800">Reviews not available</h1>
      <p className="mt-2 text-slate-600">Only people who travelled on a trip to the end can review each other.</p>
      <Link to="/reviews" className="mt-3 inline-block font-medium text-teal-700 underline">
        Your reviews
      </Link>
    </section>
  );
}

function TripReviewsLoader({ tripId }: { tripId: string }) {
  const load = useCallback(() => trustApi.tripReviews(tripId), [tripId]);
  const { state, reload } = useApiResource(load);
  const [latest, setLatest] = useState<TripReviews | null>(null);

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
  const reviews = latest ?? state.data;
  return <ReviewsView reviews={reviews} onChanged={setLatest} />;
}

function ReviewsView({ reviews, onChanged }: { reviews: TripReviews; onChanged: (next: TripReviews) => void }) {
  const { trip, window, people } = reviews;
  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Reviews: {trip.title}</h1>
      <p className="mt-1 text-sm text-slate-600">
        {trip.destinationName} · {formatDay(trip.startDate)} to {formatDay(trip.endDate)}
      </p>
      <p className="mt-3 rounded-md border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700">
        {window.status === 'NOT_REVIEWABLE' && 'This trip was cancelled or never locked, so there is nobody to review.'}
        {window.status === 'NOT_YET' && `Reviews open on ${formatDay(window.opensOn)}, the day after the trip ends.`}
        {window.status === 'OPEN' &&
          `Reviews close on ${formatDay(window.closesAt)}. Nobody sees what you wrote until they review you back or reviews close.`}
        {window.status === 'CLOSED' && 'Reviews for this trip have closed.'}
      </p>
      {people.length === 0 ? (
        <p className="mt-6 text-slate-600">Nobody else travelled on this trip with you.</p>
      ) : (
        <ul className="mt-6 space-y-4">
          {people.map((person) => (
            <li key={person.userId} className="rounded-md border border-slate-200 bg-white px-4 py-4">
              <h2 className="text-lg font-semibold text-slate-900">{person.firstName}</h2>
              <PersonBody tripId={trip.id} person={person} closesAt={window.closesAt} onChanged={onChanged} />
              <div className="mt-3 border-t border-slate-100 pt-3">
                <ReportBlock target={{ tripId: trip.id, userId: person.userId }} firstName={person.firstName} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function PersonBody({
  tripId,
  person,
  closesAt,
  onChanged,
}: {
  tripId: string;
  person: ReviewPerson;
  closesAt: string;
  onChanged: (next: TripReviews) => void;
}) {
  return (
    <div className="mt-2 space-y-2 text-sm">
      {person.status === 'TO_WRITE' && <ReviewForm tripId={tripId} person={person} onChanged={onChanged} />}
      {person.status === 'WAITING_FOR_THEM' && (
        <p className="text-slate-700">
          You reviewed {person.firstName}. It stays hidden until they review you or reviews close on {formatDay(closesAt)}.
        </p>
      )}
      {person.status === 'NOT_WRITTEN' && <p className="text-slate-600">You did not review {person.firstName}.</p>}
      {person.mine !== null && <Written label="You wrote" review={person.mine} />}
      {person.theirs !== null && <Written label={`${person.firstName} wrote`} review={person.theirs} />}
      {person.status === 'REVEALED' && person.theirs === null && (
        <p className="text-slate-600">{person.firstName} did not review you.</p>
      )}
    </div>
  );
}

function Written({ label, review }: { label: string; review: NonNullable<ReviewPerson['mine']> }) {
  return (
    <div className="rounded bg-slate-50 px-3 py-2">
      <p className="text-xs text-slate-600">{label}</p>
      <p aria-label={`${String(review.rating)} out of 5 stars`} className="text-amber-600">
        {stars(review.rating)}
      </p>
      {review.comment !== null && <p className="mt-1 whitespace-pre-line text-slate-800">{review.comment}</p>}
    </div>
  );
}

function ReviewForm({
  tripId,
  person,
  onChanged,
}: {
  tripId: string;
  person: ReviewPerson;
  onChanged: (next: TripReviews) => void;
}) {
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState('');
  const [localErrors, setLocalErrors] = useState<{ rating?: string; comment?: string }>({});
  const failure = useRequestFailure(FIELDS);
  const { pending, run } = useSubmission();

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    failure.clear();
    const errors: { rating?: string; comment?: string } = {};
    if (rating < 1 || rating > 5) {
      errors.rating = 'Choose from 1 to 5 stars.';
    }
    if (comment.trim().length > MAX_COMMENT) {
      errors.comment = `Keep the comment to ${String(MAX_COMMENT)} characters.`;
    }
    setLocalErrors(errors);
    if (errors.rating !== undefined || errors.comment !== undefined) {
      return;
    }
    void run(async () => {
      try {
        onChanged(await trustApi.review(tripId, person.userId, rating, comment));
      } catch (caught) {
        failure.report(toApiFailure(caught));
      }
    });
  }

  const ratingError = localErrors.rating ?? failure.fieldErrors.rating;
  const commentError = localErrors.comment ?? failure.fieldErrors.comment;
  return (
    <form onSubmit={submit} noValidate className="space-y-3">
      <fieldset>
        <legend className="font-medium">How was travelling with {person.firstName}?</legend>
        <div className="mt-1 flex gap-3">
          {[1, 2, 3, 4, 5].map((value) => (
            <label key={value} className="flex items-center gap-1">
              <input
                type="radio"
                name={`rating-${person.userId}`}
                value={value}
                checked={rating === value}
                onChange={() => {
                  setRating(value);
                }}
              />
              {`${String(value)} ★`}
            </label>
          ))}
        </div>
        {ratingError !== undefined && (
          <p role="alert" className="mt-1 text-red-700">
            {ratingError}
          </p>
        )}
      </fieldset>
      <div>
        <label htmlFor={`comment-${person.userId}`} className="block font-medium">
          A few words (optional)
        </label>
        <textarea
          id={`comment-${person.userId}`}
          value={comment}
          rows={3}
          maxLength={MAX_COMMENT}
          onChange={(event) => {
            setComment(event.target.value);
          }}
          className="mt-1 w-full rounded border border-slate-300 px-2 py-1"
        />
        {commentError !== undefined && (
          <p role="alert" className="text-red-700">
            {commentError}
          </p>
        )}
      </div>
      {failure.notice !== null && (
        <ErrorNotice
          message={failure.notice.message}
          correlationId={failure.notice.correlationId}
          retrySecondsLeft={failure.noticeRetrySeconds}
        />
      )}
      <p className="text-xs text-slate-600">Reviews cannot be changed once sent.</p>
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-teal-700 px-4 py-2 font-semibold text-white disabled:bg-slate-400"
      >
        {pending ? 'Sending…' : `Send review of ${person.firstName}`}
      </button>
    </form>
  );
}
