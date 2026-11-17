import { useCallback, useState } from 'react';
import { describeFailure, type ApiFailure, type FormFailureView } from '../api/problem';
import { useCountdown } from './useCountdown';

/**
 * The last failed request of a form, split into field and notice messages,
 * with the Retry-After wait counting down when the server sent one.
 */
export function useRequestFailure<F extends string>(
  formFields: readonly F[],
): FormFailureView<F> & {
  failure: ApiFailure | null;
  /** Seconds before the form may submit again; 0 when it may. */
  retrySecondsLeft: number;
  /** The countdown to show in the notice, or null when the failure carried no Retry-After. */
  noticeRetrySeconds: number | null;
  report: (failure: ApiFailure) => void;
  clear: () => void;
  clearField: (field: F) => void;
} {
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const [dismissedFields, setDismissedFields] = useState<readonly F[]>([]);
  const { secondsLeft, start } = useCountdown();

  const report = useCallback(
    (next: ApiFailure) => {
      setFailure(next);
      setDismissedFields([]);
      if (next.retryAfterSeconds !== null) {
        start(next.retryAfterSeconds);
      }
    },
    [start],
  );

  const clear = useCallback(() => {
    setFailure(null);
    setDismissedFields([]);
  }, []);

  /** Editing a field hides the server's message for it; the rest stay until the next submit. */
  const clearField = useCallback((field: F) => {
    setDismissedFields((current) => (current.includes(field) ? current : [...current, field]));
  }, []);

  const view = describeFailure(failure, formFields);
  const fieldErrors: Partial<Record<F, string>> = {};
  for (const field of formFields) {
    const message = view.fieldErrors[field];
    if (message !== undefined && !dismissedFields.includes(field)) {
      fieldErrors[field] = message;
    }
  }

  const noticeRetrySeconds = failure?.retryAfterSeconds == null ? null : secondsLeft;
  return {
    fieldErrors,
    notice: view.notice,
    failure,
    retrySecondsLeft: secondsLeft,
    noticeRetrySeconds,
    report,
    clear,
    clearField,
  };
}
