import { Link } from 'react-router';
import type { Match } from '../api/tripsApi';
import { MATCH_DIMENSION_LABELS } from '../trips/tripLabels';

/**
 * How well the caller fits a trip, and why. The score is the server's; the
 * sentences are fixed server wording about dimensions that clearly agree or
 * clearly differ, shown as text. Dimensions in between are not listed, so
 * the two lists need not cover all eight.
 */
export function MatchBreakdown({ match }: { match: Match }) {
  return (
    <section aria-labelledby="match-heading" className="rounded-md border border-slate-200 bg-white px-4 py-4">
      <h2 id="match-heading" className="font-semibold text-slate-900">
        How well you match
      </h2>
      {match.status === 'SCORED' && match.score !== null && <Scored match={match} score={match.score} />}
      {match.status === 'BLOCKED' && (
        <div className="mt-2">
          <p className="font-medium text-red-800">Not a match: a deal-breaker rules this trip out.</p>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-slate-800">
            {match.blockers.map((blocker) => (
              <li key={blocker.rule}>{blocker.label}</li>
            ))}
          </ul>
          <p className="mt-3 text-sm text-slate-600">
            You cannot ask to join it.{' '}
            <Link to="/vibe" className="font-medium text-teal-700 underline">
              Review your deal-breakers
            </Link>
          </p>
        </div>
      )}
      {match.status === 'NEEDS_YOUR_PROFILE' && (
        <p className="mt-2 text-sm text-slate-700">
          Take the vibe quiz to see how well your travel style fits this trip.{' '}
          <Link to="/vibe/quiz" className="font-medium text-teal-700 underline">
            Take the quiz
          </Link>
        </p>
      )}
      {match.status === 'HOST_HAS_NO_PROFILE' && (
        <p className="mt-2 text-sm text-slate-700">
          The host has not taken the vibe quiz yet, so there is nothing to compare your travel style with.
        </p>
      )}
    </section>
  );
}

function Scored({ match, score }: { match: Match; score: number }) {
  return (
    <>
      <div className="mt-2 flex items-center gap-3">
        <span className="text-3xl font-bold text-teal-800">{score}%</span>
        <div
          role="meter"
          aria-label="Match score"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={score}
          className="h-2 flex-1 overflow-hidden rounded bg-slate-200"
        >
          <div className="h-full bg-teal-600" style={{ width: `${String(score)}%` }} />
        </div>
      </div>
      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <ReasonList title="What fits" reasons={match.matches} empty="Nothing lines up closely." />
        <ReasonList title="What differs" reasons={match.mismatches} empty="Nothing differs sharply." />
      </div>
    </>
  );
}

function ReasonList({ title, reasons, empty }: { title: string; reasons: Match['matches']; empty: string }) {
  return (
    <div>
      <h3 className="text-sm font-semibold text-slate-800">{title}</h3>
      {reasons.length === 0 ? (
        <p className="mt-1 text-sm text-slate-500">{empty}</p>
      ) : (
        <ul className="mt-1 space-y-1 text-sm text-slate-800">
          {reasons.map((reason) => (
            <li key={reason.dimension}>
              <span className="mr-2 rounded bg-slate-100 px-1.5 py-0.5 text-xs font-medium text-slate-600">
                {MATCH_DIMENSION_LABELS[reason.dimension]}
              </span>
              {reason.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
