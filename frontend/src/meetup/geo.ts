/** Small spherical helpers for the meet-up screens. The server measures the real geofence; these only guide. */

const EARTH_RADIUS_M = 6_371_008.8;

function radians(degrees: number): number {
  return (degrees * Math.PI) / 180;
}

function degrees(radiansValue: number): number {
  return (radiansValue * 180) / Math.PI;
}

/** Great-circle distance in metres. */
export function distanceMeters(a: { latitude: number; longitude: number }, b: { latitude: number; longitude: number }): number {
  const dLat = radians(b.latitude - a.latitude);
  const dLon = radians(b.longitude - a.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(radians(a.latitude)) * Math.cos(radians(b.latitude)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** The point halfway along the great circle between two places: where two people from different cities might meet. */
export function midpoint(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): { latitude: number; longitude: number } {
  const lat1 = radians(a.latitude);
  const lat2 = radians(b.latitude);
  const dLon = radians(b.longitude - a.longitude);
  const bx = Math.cos(lat2) * Math.cos(dLon);
  const by = Math.cos(lat2) * Math.sin(dLon);
  const latitude = Math.atan2(Math.sin(lat1) + Math.sin(lat2), Math.sqrt((Math.cos(lat1) + bx) ** 2 + by ** 2));
  const longitude = radians(a.longitude) + Math.atan2(by, Math.cos(lat1) + bx);
  return { latitude: degrees(latitude), longitude: ((degrees(longitude) + 540) % 360) - 180 };
}
