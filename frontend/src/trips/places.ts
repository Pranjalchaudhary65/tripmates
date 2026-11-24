/**
 * Starting points for the place pickers, so nobody has to know a latitude.
 * Each is the rough centre of a town or region, which is all a trip's
 * destination point and a browse radius need; the create form still takes
 * exact coordinates for anywhere else.
 */
export interface Place {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
}

export const PLACES: readonly Place[] = [
  { id: 'bengaluru', name: 'Bengaluru, Karnataka', latitude: 12.9716, longitude: 77.5946 },
  { id: 'darjeeling', name: 'Darjeeling, West Bengal', latitude: 27.041, longitude: 88.2663 },
  { id: 'delhi', name: 'Delhi', latitude: 28.6139, longitude: 77.209 },
  { id: 'goa', name: 'Goa', latitude: 15.2993, longitude: 74.124 },
  { id: 'gokarna', name: 'Gokarna, Karnataka', latitude: 14.5479, longitude: 74.3188 },
  { id: 'hampi', name: 'Hampi, Karnataka', latitude: 15.335, longitude: 76.46 },
  { id: 'jaipur', name: 'Jaipur, Rajasthan', latitude: 26.9124, longitude: 75.7873 },
  { id: 'leh', name: 'Leh, Ladakh', latitude: 34.1526, longitude: 77.5771 },
  { id: 'manali', name: 'Manali, Himachal Pradesh', latitude: 32.2432, longitude: 77.1892 },
  { id: 'mumbai', name: 'Mumbai, Maharashtra', latitude: 19.076, longitude: 72.8777 },
  { id: 'munnar', name: 'Munnar, Kerala', latitude: 10.0889, longitude: 77.0595 },
  { id: 'puducherry', name: 'Puducherry', latitude: 11.9416, longitude: 79.8083 },
  { id: 'rishikesh', name: 'Rishikesh, Uttarakhand', latitude: 30.0869, longitude: 78.2676 },
  { id: 'udaipur', name: 'Udaipur, Rajasthan', latitude: 24.5854, longitude: 73.7125 },
  { id: 'varanasi', name: 'Varanasi, Uttar Pradesh', latitude: 25.3176, longitude: 82.9739 },
];

export function placeById(id: string): Place | null {
  return PLACES.find((place) => place.id === id) ?? null;
}

/** Browse radii on offer, all within the server's 1000 km cap. */
export const SEARCH_RADII_KM: readonly number[] = [25, 50, 100, 250, 500, 1000];
