export type ItineraryStop = {
    id: number; title: string; location: string | null; description: string | null;
    starts_at: string; ends_at: string; transport_mode: string | null;
    latitude?: number | null; longitude?: number | null;
    cost_min_minor?: number | null; cost_max_minor?: number | null; cost_note?: string | null;
};

export const transportOptions = [
    { value: 'car', label: 'Car', route: 'DRIVING' },
    { value: 'van', label: 'Van', route: 'DRIVING' },
    { value: 'taxi', label: 'Taxi / ride-hailing', route: 'DRIVING' },
    { value: 'motorcycle', label: 'Motorcycle', route: 'TWO_WHEELER' },
    { value: 'tricycle', label: 'Tricycle (driving estimate)', route: 'DRIVING' },
    { value: 'walking', label: 'Walking', route: 'WALKING' },
    { value: 'bicycle', label: 'Bicycle', route: 'BICYCLING' },
    { value: 'transit', label: 'Public transit', route: 'TRANSIT' },
    { value: 'ferry', label: 'Ferry / boat', route: null },
    { value: 'flight', label: 'Flight', route: null },
    { value: 'other', label: 'Other transport', route: null },
] as const;

export const transport = (value: string | null) => transportOptions.find(option => option.value === value);
export const orderedStops = (items: ItineraryStop[]) => [...items].sort((a, b) => Date.parse(a.starts_at) - Date.parse(b.starts_at) || a.id - b.id);
export function directionsUrl(from: string, to: string, mode: string): string {
    const modes: Record<string, string> = { DRIVING: 'driving', WALKING: 'walking', BICYCLING: 'bicycling', TRANSIT: 'transit', TWO_WHEELER: 'two-wheeler' };
    return `https://www.google.com/maps/dir/?${new URLSearchParams({ api: '1', origin: from, destination: to, travelmode: modes[mode] ?? 'driving' })}`;
}

let mapsLoading: Promise<void> | undefined;
export function loadGoogleMaps(key: string): Promise<void> {
    if (typeof google !== 'undefined' && typeof google.maps?.importLibrary === 'function') return Promise.resolve();
    if (mapsLoading) return mapsLoading;
    mapsLoading = new Promise<void>((resolve, reject) => {
        const script = document.createElement('script');
        const globals = window as unknown as Record<string, unknown>;
        const timer = window.setTimeout(() => fail(), 20000);
        const fail = () => {
            clearTimeout(timer); script.remove(); mapsLoading = undefined;
            delete globals.barKadaMapsReady;
            reject(new Error('Google Maps could not load.'));
        };
        globals.barKadaMapsReady = () => { clearTimeout(timer); delete globals.barKadaMapsReady; resolve(); };
        script.src = `https://maps.googleapis.com/maps/api/js?${new URLSearchParams({ key, v: 'weekly', loading: 'async', callback: 'barKadaMapsReady' })}`;
        script.async = true;
        script.onerror = fail;
        document.head.appendChild(script);
    });
    return mapsLoading;
}

/** Calendar day (YYYY-MM-DD) of an ISO time in the trip's timezone. */
export function dayKey(value: string, timeZone: string): string {
    return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value));
}

/** Short label for a day key, e.g. "Wed, Nov 11". */
export function dayLabel(key: string): string {
    return new Intl.DateTimeFormat('en-PH', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(`${key}T00:00:00Z`));
}

/** True when two consecutive stops are effectively the same place (no travel needed). */
export function samePlace(a: ItineraryStop, b: ItineraryStop): boolean {
    if (a.latitude != null && a.longitude != null && b.latitude != null && b.longitude != null) {
        // ~50 m: close enough to walk without a route.
        return Math.abs(a.latitude - b.latitude) < 0.00045 && Math.abs(a.longitude - b.longitude) < 0.00045;
    }
    return !!a.location && a.location.trim().toLowerCase() === b.location?.trim().toLowerCase();
}

const peso = (minor: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP', maximumFractionDigits: minor % 100 ? 2 : 0 }).format(minor / 100);

/** "₱280–440", "₱300", or '' when there is no estimate. */
export function costRange(min?: number | null, max?: number | null): string {
    if (min == null || max == null) return '';
    return min === max ? peso(min) : `${peso(min)}–${peso(max).replace('₱', '')}`;
}

/** Sum of per-person estimates over stops, or null when none have one. */
export function totalCost(stops: ItineraryStop[]): { min: number; max: number } | null {
    const priced = stops.filter(stop => stop.cost_min_minor != null && stop.cost_max_minor != null);
    if (!priced.length) return null;
    return priced.reduce((sum, stop) => ({ min: sum.min + stop.cost_min_minor!, max: sum.max + stop.cost_max_minor! }), { min: 0, max: 0 });
}

export type ActivityKind = 'food' | 'flight' | 'ferry' | 'stay' | 'water' | 'sight' | 'shop' | 'place';
const kindRules: [ActivityKind, RegExp][] = [
    ['flight', /\b(flight|airport|depart manila|arrive in manila)\b/i],
    ['ferry', /\b(ferry|port|boat|pier)\b/i],
    ['food', /\b(breakfast|lunch|dinner|brunch|cafe|café|coffee|restaurant|food|eat|snack)\b/i],
    ['stay', /\b(check in|check-in|check out|hotel|resort|night|rest|relax|cove|inn|hostel)\b/i],
    ['water', /\b(beach|snorkel|swim|dive|falls|spring|sunset|island)\b/i],
    ['shop', /\b(shop|souvenir|market|mall)\b/i],
    ['sight', /\b(church|cave|tour|museum|viewpoint|tree|park|sightseeing|hike|boulevard)\b/i],
];
/** Best-guess activity type from its title, for an icon only. */
export function activityKind(title: string): ActivityKind {
    return kindRules.find(([, rule]) => rule.test(title))?.[0] ?? 'place';
}

/** Google Maps directions through a day's stops in order (consecutive duplicates removed). */
export function dayDirectionsUrl(stops: ItineraryStop[]): string | null {
    const points: string[] = [];
    for (const stop of stops) {
        if (!stop.location) continue;
        const point = stop.latitude != null && stop.longitude != null ? `${stop.latitude},${stop.longitude}` : stop.location;
        if (points[points.length - 1] !== point) points.push(point);
    }
    if (points.length < 2) return null;
    // The Maps URL API accepts at most 9 waypoints between origin and destination.
    const waypoints = points.slice(1, -1).slice(0, 9);
    const params = new URLSearchParams({ api: '1', origin: points[0], destination: points[points.length - 1], travelmode: 'driving' });
    if (waypoints.length) params.set('waypoints', waypoints.join('|'));
    return `https://www.google.com/maps/dir/?${params}`;
}
