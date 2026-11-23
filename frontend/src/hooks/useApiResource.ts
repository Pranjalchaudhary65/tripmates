import { useCallback, useEffect, useState } from 'react';
import { toApiFailure, type ApiFailure } from '../api/problem';

export type ResourceState<T> =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ApiFailure }
  | { kind: 'ready'; data: T };

/**
 * Loads one thing a screen shows, with the loading and error states every
 * screen needs. A response that arrives after the screen has gone, or after
 * a newer reload, is dropped rather than shown.
 */
export function useApiResource<T>(load: () => Promise<T>): { state: ResourceState<T>; reload: () => void } {
  const [state, setState] = useState<ResourceState<T>>({ kind: 'loading' });
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    let current = true;
    load().then(
      (data) => {
        if (current) {
          setState({ kind: 'ready', data });
        }
      },
      (error: unknown) => {
        if (current) {
          setState({ kind: 'failed', failure: toApiFailure(error) });
        }
      },
    );
    return () => {
      current = false;
    };
  }, [load, generation]);

  const reload = useCallback(() => {
    setState({ kind: 'loading' });
    setGeneration((value) => value + 1);
  }, []);

  return { state, reload };
}
