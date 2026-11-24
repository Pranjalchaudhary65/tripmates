import { Link } from 'react-router';
import type { TripSummary } from '../api/tripsApi';
import {
  formatTripDates,
  PERSON_TIER_LABELS,
  placesLeft,
  REQUEST_STATUS_LABELS,
  TRIP_STATUS_LABELS,
} from '../trips/tripLabels';
import { BUDGET_LABELS } from '../vibe/vibeLabels';

/** One trip in a list, linking to its page. Everything shown is text React escapes. */
export function TripCard({ trip }: { trip: TripSummary }) {
  return (
    <li className="rounded-md border border-slate-200 bg-white px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Link to={`/trips/${trip.id}`} className="text-lg font-semibold text-teal-800 underline">
          {trip.title}
        </Link>
        <span className="text-sm text-slate-600">
          {trip.matchScore !== null && (
            <span className="mr-2 rounded bg-teal-50 px-2 py-0.5 font-semibold text-teal-800">
              {trip.matchScore}% match
            </span>
          )}
          {TRIP_STATUS_LABELS[trip.status]}
        </span>
      </div>
      <p className="mt-1 text-sm text-slate-700">
        {trip.destinationName}
        {trip.distanceKm !== null && ` · ${formatDistance(trip.distanceKm)} away`}
      </p>
      <p className="mt-1 text-sm text-slate-700">
        {formatTripDates(trip.startDate, trip.endDate)} · {BUDGET_LABELS[trip.budgetBand]} ·{' '}
        {placesLeft(trip.totalSlots, trip.filledSlots)}
      </p>
      <p className="mt-1 text-sm text-slate-600">
        {trip.hostedByMe
          ? 'You are hosting'
          : `Hosted by ${trip.host.firstName} (${PERSON_TIER_LABELS[trip.host.verificationTier]})`}
        {trip.myRequest !== null && ` · Your request: ${REQUEST_STATUS_LABELS[trip.myRequest.status]}`}
        {trip.pendingRequests !== null &&
          trip.pendingRequests > 0 &&
          ` · ${String(trip.pendingRequests)} waiting for your answer`}
      </p>
    </li>
  );
}

function formatDistance(km: number): string {
  return km < 1 ? 'under 1 km' : `${String(Math.round(km))} km`;
}
