import { useCallback } from 'react';
import { Link } from 'react-router';
import { trustApi, type TrustBreakdown } from '../api/trustApi';
import { ErrorNotice } from '../components/ErrorNotice';
import { useApiResource } from '../hooks/useApiResource';
import { deductionLabel, formatDay, formatPoints, TIER_NAMES } from '../trust/trustLabels';

/**
 * The user's own trust score and exactly how it was reached. Nobody else
 * ever sees this number: it only decides whose minimum-score filter they
 * pass.
 */
export function TrustScorePage() {
  const load = useCallback(() => trustApi.myScore(), []);
  const { state, reload } = useApiResource(load);

  return (
    <section className="max-w-2xl">
      <h1 className="text-2xl font-bold text-teal-800">Your trust score</h1>
      <p className="mt-2 text-sm text-slate-600">
        Other travellers never see this number. It only decides whether you pass the minimum score they set when
        browsing.
      </p>
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
        {state.kind === 'ready' &&
          (state.data.pending ? (
            <p role="status" className="rounded-md border border-slate-200 bg-white px-4 py-3 text-slate-700">
              Your score is being calculated. Check back in a minute.
            </p>
          ) : (
            <ScoreView score={state.data.score} computedAt={state.data.computedAt} breakdown={state.data.breakdown} />
          ))}
      </div>
      <p className="mt-8 text-sm">
        <Link to="/account/blocked" className="font-medium text-teal-700 underline">
          People you have blocked
        </Link>
      </p>
    </section>
  );
}

function ScoreView({ score, computedAt, breakdown }: { score: number; computedAt: string; breakdown: TrustBreakdown }) {
  const { verification, peerRating, completion, reliability, vouchPenalty } = breakdown;
  const rows = [
    {
      name: 'Verification',
      basis: `${TIER_NAMES[verification.tier]} (${String(verification.tierPoints)} points)${
        verification.activeVouchers > 0
          ? `, ${String(verification.activeVouchers)} vouching for you (+${String(verification.vouchBonus)})`
          : ''
      }, out of 60`,
      component: verification,
    },
    {
      name: 'Reviews',
      basis:
        peerRating.visibleReviews === 0
          ? 'No reviews yet. You start from an average of 3.5 stars.'
          : `${String(peerRating.visibleReviews)} review${peerRating.visibleReviews === 1 ? '' : 's'}, ${String(
              peerRating.averageStars,
            )} stars on average; weighted with newer reviews counting more: ${String(peerRating.weightedStars)}`,
      component: peerRating,
    },
    {
      name: 'Trips completed',
      basis:
        completion.finishedTrips === 0
          ? 'No finished trips yet. New accounts start at 50.'
          : `${String(completion.finishedTrips)} trip${completion.finishedTrips === 1 ? '' : 's'} travelled to the end`,
      component: completion,
    },
    {
      name: 'Reliability',
      basis:
        reliability.deductions.length === 0
          ? 'Nothing against you.'
          : `${String(reliability.deductions.length)} deduction${reliability.deductions.length === 1 ? '' : 's'}, listed below`,
      component: reliability,
    },
  ];

  return (
    <div className="space-y-6">
      <div className="rounded-md border border-teal-200 bg-teal-50 px-4 py-4">
        <p className="text-4xl font-bold text-teal-900" aria-label={`Score ${String(score)} out of 100`}>
          {score.toFixed(2)}
          <span className="text-lg font-medium text-teal-700"> / 100</span>
        </p>
        <p className="mt-1 text-xs text-teal-800">Worked out on {formatDay(computedAt)}</p>
      </div>

      <table className="w-full text-left text-sm">
        <caption className="sr-only">How your score is made up</caption>
        <thead>
          <tr className="border-b border-slate-200 text-slate-600">
            <th scope="col" className="py-2 pr-2">
              Part
            </th>
            <th scope="col" className="py-2 pr-2">
              Out of 100
            </th>
            <th scope="col" className="py-2 pr-2">
              Weight
            </th>
            <th scope="col" className="py-2">
              Adds
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.name} className="border-b border-slate-100 align-top">
              <th scope="row" className="py-2 pr-2 font-medium">
                {row.name}
                <p className="text-xs font-normal text-slate-600">{row.basis}</p>
              </th>
              <td className="py-2 pr-2">{formatPoints(row.component.value)}</td>
              <td className="py-2 pr-2">{`${String(Math.round(row.component.weight * 100))}%`}</td>
              <td className="py-2">{row.component.points.toFixed(2)}</td>
            </tr>
          ))}
          {vouchPenalty.count > 0 && (
            <tr className="align-top">
              <th scope="row" className="py-2 pr-2 font-medium">
                Vouching penalty
                <p className="text-xs font-normal text-slate-600">
                  {`${String(vouchPenalty.count)} account${vouchPenalty.count === 1 ? '' : 's'} you vouched for ${
                    vouchPenalty.count === 1 ? 'was' : 'were'
                  } suspended (at most 9 points)`}
                </p>
              </th>
              <td />
              <td />
              <td className="py-2">{`−${vouchPenalty.points.toFixed(2)}`}</td>
            </tr>
          )}
        </tbody>
      </table>

      {reliability.deductions.length > 0 && (
        <div>
          <h2 className="text-lg font-semibold text-slate-900">Deductions</h2>
          <p className="text-sm text-slate-600">Each one fades with time. Not turning up fades to half and stays there.</p>
          <ul className="mt-2 space-y-2">
            {reliability.deductions.map((deduction) => (
              <li
                key={`${deduction.type}-${deduction.occurredAt}`}
                className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm"
              >
                <p className="font-medium">{deductionLabel(deduction)}</p>
                <p className="text-xs text-slate-600">
                  {formatDay(deduction.occurredAt)}: −{formatPoints(deduction.fullPoints)} then, −
                  {formatPoints(deduction.nowPoints)} today
                </p>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="rounded-md border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700">
        <h2 className="font-semibold text-slate-900">Never counted</h2>
        <p className="mt-1">
          Pressing SOS, missing a check-in, stepping back from a trip for your safety, how you rated a meet-up, and
          whether you reply to messages. Staying safe never costs you anything.
        </p>
      </div>

      {verification.tier !== 'SELFIE_MATCH' && (
        <p className="text-sm">
          <Link to="/account/verification" className="font-medium text-teal-700 underline">
            Verify more to raise your score
          </Link>
        </p>
      )}
    </div>
  );
}
