export interface ErrorNoticeProps {
  message: string;
  /** Shown so the user can quote it; it is the only link to the server log entry. */
  correlationId?: string | null;
  /** Seconds until a retry is allowed, for a failure that came with Retry-After. */
  retrySecondsLeft?: number | null;
}

/**
 * The one place a failed request is shown outside a form field. It only
 * ever renders text that has already been through toApiFailure, never a
 * raw response body or exception message.
 */
export function ErrorNotice({ message, correlationId = null, retrySecondsLeft = null }: ErrorNoticeProps) {
  return (
    <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      <p className="font-medium">{message}</p>
      {retrySecondsLeft !== null && (
        <p className="mt-1">
          {retrySecondsLeft > 0 ? `You can try again in ${formatWait(retrySecondsLeft)}.` : 'You can try again now.'}
        </p>
      )}
      {correlationId !== null && (
        <p className="mt-2 text-xs text-red-700">
          If this keeps happening, contact support and quote reference{' '}
          <code className="font-mono select-all">{correlationId}</code>.
        </p>
      )}
    </div>
  );
}

function formatWait(seconds: number): string {
  if (seconds < 60) {
    return seconds === 1 ? '1 second' : `${String(seconds)} seconds`;
  }
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${String(minutes)}:${String(rest).padStart(2, '0')} minutes`;
}
