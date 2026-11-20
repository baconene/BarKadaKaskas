/**
 * One Routes API request per journey per page visit. Both trip maps (the Map screen and the presentation) ask for
 * routes through here, so redrawing after an edit, toggling a stop done, reopening a presentation slide or watching
 * the presentation again reuses the route already fetched instead of paying for it again. Requests in flight are
 * shared, and "no route" answers (e.g. across water) are remembered too. Errors that may pass (permission, quota,
 * network) are not remembered, so a later redraw can try again.
 */
type End = string | google.maps.LatLngLiteral;
// Every caller asks for the same fields (all in the Basic tier), so both maps share one cache entry per journey.
const FIELDS = ['path', 'distanceMeters', 'durationMillis', 'warnings'];
const cache = new Map<string, Promise<google.maps.routes.Route | null>>();
const MAX_ENTRIES = 500;

const endKey = (end: End) => (typeof end === 'string' ? end.trim().toLowerCase() : `${end.lat.toFixed(5)},${end.lng.toFixed(5)}`);
const passing = (error: unknown) => /PERMISSION_DENIED|RESOURCE_EXHAUSTED|UNAVAILABLE|DEADLINE|network|fetch/i.test(String(error));

/** The first route between two places for a travel mode, or null when Google has none. Throws on passing errors. */
export function computeRoute(routes: google.maps.RoutesLibrary, origin: End, destination: End, travelMode: string): Promise<google.maps.routes.Route | null> {
    const key = `${travelMode}|${endKey(origin)}|${endKey(destination)}`;
    const known = cache.get(key);
    if (known) return known;
    const request = routes.Route.computeRoutes({ origin, destination, travelMode: travelMode as google.maps.TravelMode, fields: FIELDS })
        .then(response => response.routes?.[0] ?? null)
        .then(route => (route?.path?.length ? route : null))
        .catch((error: unknown) => {
            if (passing(error)) { cache.delete(key); throw error; }
            return null;
        });
    if (cache.size >= MAX_ENTRIES) cache.delete(cache.keys().next().value!);
    cache.set(key, request);
    return request;
}
