import { useCallback, useEffect, useState } from 'react';

const TICK_MS = 250;

/**
 * Seconds left until a deadline, re-rendering as they tick down.
 *
 * Counts against the wall clock rather than decrementing once per interval:
 * browsers throttle timers in background tabs, so a counter that subtracts 1
 * per tick would still say 40s after the real wait was long over.
 */
export function useCountdown(initialEndsAt: number | null = null): {
  secondsLeft: number;
  start: (seconds: number) => void;
} {
  const [endsAt, setEndsAt] = useState<number | null>(initialEndsAt);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (endsAt === null) {
      return undefined;
    }
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= endsAt) {
        setEndsAt(null);
      }
    }, TICK_MS);
    return () => {
      clearInterval(timer);
    };
  }, [endsAt]);

  const start = useCallback((seconds: number) => {
    const current = Date.now();
    setNow(current);
    setEndsAt(current + seconds * 1000);
  }, []);

  const secondsLeft = endsAt === null ? 0 : Math.max(0, Math.ceil((endsAt - now) / 1000));
  return { secondsLeft, start };
}
