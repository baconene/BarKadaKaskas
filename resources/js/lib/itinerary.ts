export type ItineraryStop = {
    id: number; title: string; location: string | null; description: string | null;
    starts_at: string; ends_at: string; transport_mode: string | null;
    latitude?: number | null; longitude?: number | null;
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
