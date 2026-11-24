/**
 * The phone's position for a meet-up check-in, in one place so screens never
 * call the Geolocation API directly and tests can stand in for it. Asks for
 * the precise fix, fresh rather than cached: an old position is exactly what
 * a check-in must not use.
 */

export type LocationProblem = 'unsupported' | 'denied' | 'unavailable' | 'timeout';

export class LocationError extends Error {
  readonly problem: LocationProblem;

  constructor(problem: LocationProblem) {
    super(problem);
    this.problem = problem;
  }
}

export interface Position {
  latitude: number;
  longitude: number;
  /** Metres, as the device reports it. */
  accuracy: number;
}

const TIMEOUT_MS = 20_000;

export function currentPosition(): Promise<Position> {
  const geolocation = typeof navigator === 'undefined' ? undefined : navigator.geolocation;
  if (geolocation === undefined) {
    return Promise.reject(new LocationError('unsupported'));
  }
  return new Promise((resolve, reject) => {
    geolocation.getCurrentPosition(
      (position) => {
        resolve({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
        });
      },
      (error) => {
        reject(
          new LocationError(
            error.code === error.PERMISSION_DENIED ? 'denied' : error.code === error.TIMEOUT ? 'timeout' : 'unavailable',
          ),
        );
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: TIMEOUT_MS },
    );
  });
}
