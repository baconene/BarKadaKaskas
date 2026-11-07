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
