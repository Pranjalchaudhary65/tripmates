import { useCallback, useRef, useState } from 'react';

/**
 * Runs one submission at a time. `pending` drives the disabled button, but
 * a state update only lands on the next render, so two clicks in the same
 * frame would both see pending=false. The ref is set synchronously and is
 * what actually blocks the second submit.
 */
export function useSubmission(): { pending: boolean; run: (task: () => Promise<void>) => Promise<void> } {
  const inFlight = useRef(false);
  const [pending, setPending] = useState(false);

  const run = useCallback(async (task: () => Promise<void>) => {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    setPending(true);
    try {
      await task();
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  }, []);

  return { pending, run };
}
