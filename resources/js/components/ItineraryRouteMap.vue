<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { dayKey, loadGoogleMaps, orderedStops, samePlace, transport, type ItineraryStop } from '@/lib/itinerary';

/**
 * The trip's Google map. It draws every journey once and then only shows or hides overlays,
 * so focusing a journey, switching days or switching screens never re-requests routes.
 */
const props = defineProps<{ items: ItineraryStop[]; browserKey: string; mapId: string; timezone: string; day: string | null; active: boolean }>();
const canvas = ref<HTMLElement>();
const stops = computed(() => orderedStops(props.items));
const legs = computed(() => stops.value.slice(1).map((to, i) => ({ from: stops.value[i], to, number: i + 1, mode: transport(to.transport_mode) })));
type Leg = (typeof legs.value)[number];
// A journey belongs to the day it arrives.
const dayLegs = computed(() => (props.day ? legs.value.filter(leg => dayKey(leg.to.starts_at, props.timezone) === props.day) : legs.value));
// Id of the arrival stop of the focused journey; shared with the screens.
const selectedId = defineModel<number | null>('selected', { default: null });
const visibleLegs = computed(() => {
    const focused = dayLegs.value.find(leg => leg.to.id === selectedId.value);
    return focused ? [focused] : dayLegs.value;
});

export type LegResult = { summary: string; warnings: string[]; direct?: boolean; same?: boolean; meters?: number; seconds?: number };
const results = ref<Record<number, LegResult>>({});
const busy = ref(false);
const error = ref('');
const notice = ref('');
const emit = defineEmits<{ results: [Record<number, LegResult>]; busy: [boolean] }>();
watch(results, value => emit('results', { ...value }), { deep: true });
watch(busy, value => emit('busy', value));

let generation = 0;
let map: google.maps.Map | undefined;
type Overlay = { lines: google.maps.Polyline[]; points: (google.maps.LatLngLiteral | google.maps.LatLng)[] };
const overlays = new Map<number, Overlay>();
const markers = new Map<number, google.maps.marker.AdvancedMarkerElement>();
// Addresses geocoded during this page view, so redraws don't repeat lookups.
const geocodeCache = new Map<string, google.maps.LatLngLiteral | null>();
// Once Google refuses geocoding (e.g. no billing), stop asking for the rest of the visit.
let geocodingBlocked = false;
function clear() {
    overlays.forEach(o => o.lines.forEach(line => line.setMap(null)));
    markers.forEach(marker => { marker.map = null; });
    overlays.clear(); markers.clear();
}

const lineStyles: Record<string, { color: string; symbol: google.maps.Symbol; repeat: string }> = {
    flight: { color: '#6b7f3a', symbol: { path: 'M 0,-1 0,1', strokeOpacity: 1, scale: 3 }, repeat: '16px' },
    // 0 = google.maps.SymbolPath.CIRCLE (the enum isn't available until the Maps script loads).
    ferry: { color: '#2f7fa6', symbol: { path: 0 as google.maps.SymbolPath, fillOpacity: 1, strokeOpacity: 0, scale: 3 }, repeat: '12px' },
    fallback: { color: '#8a6d3b', symbol: { path: 'M 0,-1 0,1', strokeOpacity: 1, scale: 2 }, repeat: '10px' },
};

/** Show only the overlays of visible journeys and frame them. */
function applyVisibility() {
    if (!map) return;
    const shown = new Set(visibleLegs.value.map(leg => leg.to.id));
    const shownStops = new Set<number>();
    const bounds = new google.maps.LatLngBounds();
    for (const leg of legs.value) {
        const overlay = overlays.get(leg.to.id);
        if (!overlay) continue;
        const on = shown.has(leg.to.id);
        overlay.lines.forEach(line => line.setMap(on ? map! : null));
        if (on) { shownStops.add(leg.from.id); shownStops.add(leg.to.id); overlay.points.forEach(point => bounds.extend(point)); }
    }
    markers.forEach((marker, stopId) => { marker.map = shownStops.has(stopId) ? map! : null; });
    if (bounds.isEmpty() || !props.active) return;
    if (bounds.getNorthEast().equals(bounds.getSouthWest())) { map.setCenter(bounds.getCenter()); map.setZoom(15); } else map.fitBounds(bounds, 48);
}

async function draw() {
    const version = ++generation;
    clear(); results.value = {}; error.value = ''; notice.value = ''; busy.value = false;
    if (!props.browserKey || !canvas.value || !legs.value.length) return;
    busy.value = true;
    try {
        await loadGoogleMaps(props.browserKey);
        const [maps, routes, markerLibrary, geocoding, geometry] = await Promise.all([
            google.maps.importLibrary('maps') as Promise<google.maps.MapsLibrary>,
            google.maps.importLibrary('routes') as Promise<google.maps.RoutesLibrary>,
            google.maps.importLibrary('marker') as Promise<google.maps.MarkerLibrary>,
            google.maps.importLibrary('geocoding') as Promise<google.maps.GeocodingLibrary>,
            google.maps.importLibrary('geometry') as Promise<google.maps.GeometryLibrary>,
        ]);
        if (version !== generation || !canvas.value) return;
        if (!map || map.getDiv() !== canvas.value) {
            map = new maps.Map(canvas.value, { center: { lat: 12.88, lng: 121.77 }, zoom: 5, mapId: props.mapId || 'DEMO_MAP_ID', mapTypeControl: false, streetViewControl: false, fullscreenControl: false, gestureHandling: 'greedy', clickableIcons: false });
            // Live traffic on the roads, so travel times on the plan can be read against current conditions.
            new maps.TrafficLayer().setMap(map);
        }
        const geocoder = new geocoding.Geocoder();
        let geocodeDenied = geocodingBlocked;

        const locate = async (address: string): Promise<google.maps.LatLngLiteral | null> => {
            if (geocodeCache.has(address)) return geocodeCache.get(address)!;
            if (geocodingBlocked) return null;
            try {
                const { results: found } = await geocoder.geocode({ address });
                const point = found[0]?.geometry.location.toJSON() ?? null;
                geocodeCache.set(address, point);
                return point;
            } catch (e) {
                if (String(e).includes('REQUEST_DENIED')) { geocodingBlocked = true; geocodeDenied = true; } else geocodeCache.set(address, null);
                return null;
            }
        };
        // Prefer the exact coordinates saved from place search; otherwise look up the typed address.
        const saved = (stop: ItineraryStop) => (stop.latitude != null && stop.longitude != null ? { lat: Number(stop.latitude), lng: Number(stop.longitude) } : null);
        const position = async (stop: ItineraryStop) => saved(stop) ?? locate(stop.location!);
        const routeEnd = (stop: ItineraryStop) => saved(stop) ?? stop.location!;
        const marker = (stop: ItineraryStop, position: google.maps.LatLngLiteral | google.maps.LatLng, number: number) => {
            if (markers.has(stop.id)) return;
            const label = document.createElement('span'); label.textContent = String(number);
            label.style.cssText = 'background:#254d3d;color:white;border:2px solid white;border-radius:50%;min-width:30px;height:30px;display:grid;place-items:center;font:700 12px system-ui;box-shadow:0 2px 6px #0003';
            const pin = new markerLibrary.AdvancedMarkerElement({ position, title: `${number}. ${stop.title}`, content: label, gmpClickable: true });
            // Tapping a pin focuses the journey that arrives there.
            pin.addEventListener('gmp-click', () => { if (stop.id !== stops.value[0]?.id) selectedId.value = stop.id; });
            markers.set(stop.id, pin);
        };
        const directLine = async (leg: Leg, style: keyof typeof lineStyles): Promise<{ meters?: number; problem?: string } | null> => {
            const [from, to] = await Promise.all([position(leg.from), position(leg.to)]);
            if (version !== generation) return null;
            if (!from || !to) return { problem: geocodeDenied ? 'Can’t place this journey: pick both locations from the search suggestions to pin them.' : 'A location couldn’t be found. Pick it from the search suggestions.' };
            const { color, symbol, repeat } = lineStyles[style];
            const line = new maps.Polyline({ path: [from, to], geodesic: true, strokeOpacity: 0, icons: [{ icon: { ...symbol, strokeColor: color, fillColor: color }, offset: '0', repeat }] });
            overlays.set(leg.to.id, { lines: [line], points: [from, to] });
            marker(leg.from, from, leg.number); marker(leg.to, to, leg.number + 1);
            return { meters: geometry.spherical.computeDistanceBetween(from, to) };
        };
        const km = (meters: number) => (meters < 10000 ? (meters / 1000).toFixed(1) : Math.round(meters / 1000).toLocaleString());

        for (const leg of legs.value) {
            if (version !== generation) return;
            if (!leg.from.location || !leg.to.location) continue;
            if (samePlace(leg.from, leg.to)) {
                // Nothing to route; pin the spot so focusing the journey still frames it.
                const spot = saved(leg.from) ?? saved(leg.to);
                if (spot) { marker(leg.from, spot, leg.number); marker(leg.to, spot, leg.number + 1); overlays.set(leg.to.id, { lines: [], points: [spot] }); }
                results.value[leg.to.id] = { summary: 'Same spot — no travel needed', warnings: [], same: true, meters: 0, seconds: 0 };
                applyVisibility();
                continue;
            }
            if (!leg.mode) continue;
            if (!leg.mode.route) {
                const direct = await directLine(leg, leg.mode.value === 'flight' ? 'flight' : 'ferry');
                if (version !== generation || !direct) return;
                results.value[leg.to.id] = direct.meters != null ? { summary: `≈ ${km(direct.meters)} km direct`, warnings: [], direct: true, meters: direct.meters } : { summary: direct.problem!, warnings: [] };
                applyVisibility();
                continue;
            }
            try {
                const response = await routes.Route.computeRoutes({ origin: routeEnd(leg.from), destination: routeEnd(leg.to), travelMode: leg.mode.route, fields: ['path', 'distanceMeters', 'durationMillis', 'warnings'] });
                if (version !== generation) return;
                const route = response.routes?.[0];
                if (!route?.path?.length) throw new Error('No route');
                overlays.set(leg.to.id, { lines: route.createPolylines(), points: route.path });
                marker(leg.from, route.path[0], leg.number); marker(leg.to, route.path[route.path.length - 1], leg.number + 1);
                const meters = route.distanceMeters ?? 0; const seconds = (route.durationMillis ?? 0) / 1000;
                results.value[leg.to.id] = { summary: `${km(meters)} km · about ${Math.max(1, Math.ceil(seconds / 60))} min`, warnings: route.warnings ?? [], meters, seconds };
            } catch (e) {
                if (version !== generation) return;
                const message = String(e);
                if (message.includes('PERMISSION_DENIED')) { results.value[leg.to.id] = { summary: 'Google denied route access. Check that Routes API is enabled for this key.', warnings: [] }; continue; }
                if (message.includes('RESOURCE_EXHAUSTED')) { results.value[leg.to.id] = { summary: 'Google routing quota reached. Try again later.', warnings: [] }; continue; }
                // No road/transit route (e.g. across water): show the journey as a direct line instead.
                const direct = await directLine(leg, 'fallback');
                if (version !== generation) return;
                results.value[leg.to.id] = direct?.meters != null
                    ? { summary: `≈ ${km(direct.meters)} km direct · no ${leg.mode.label.toLowerCase()} route found`, warnings: [], direct: true, meters: direct.meters }
                    : { summary: 'Route unavailable. Check the locations or try another transport type.', warnings: [] };
            }
            applyVisibility();
        }
        if (geocodeDenied) notice.value = 'Some stops were typed without picking a Google suggestion, so they can’t be pinned. Edit them and choose the place from the suggestions.';
    } catch {
        if (version === generation) error.value = 'The map could not load. You can still open journeys in Google Maps.';
    } finally { if (version === generation) busy.value = false; }
}

watch([() => props.items, () => props.browserKey, canvas], draw, { deep: true, flush: 'post' });
watch(visibleLegs, () => applyVisibility());
// The map lives in one place but is shown in different layouts; reframe when it becomes visible.
watch(() => props.active, active => { if (active) nextTick(() => applyVisibility()); });
onBeforeUnmount(() => { generation++; clear(); });
</script>

<template>
    <div class="route-map-wrap">
        <div v-if="browserKey && stops.length >= 2" ref="canvas" class="route-map" aria-label="Google Maps trip route" role="region" />
        <div v-else class="route-map route-map-empty">
            <p class="muted">{{ !browserKey ? 'The interactive map is not enabled yet. Use the Google Maps links on each activity.' : 'Add at least two stops with locations to see your route.' }}</p>
        </div>
        <div v-if="busy" class="route-map-status" role="status"><span class="skeleton" aria-hidden="true" /> Drawing your route…</div>
        <p v-if="notice" role="status" class="route-map-notice">{{ notice }}</p>
        <p v-if="error" role="alert" class="route-map-notice">{{ error }} <button type="button" class="btn secondary" @click="draw">Retry</button></p>
    </div>
</template>

<style scoped>
.route-map-wrap{position:relative;height:100%;display:flex;flex-direction:column;border-radius:inherit}
.route-map{flex:1;min-height:220px;border-radius:inherit;background:#e8eee2}
.route-map-empty{display:grid;place-items:center;padding:24px;text-align:center}
.route-map-status{position:absolute;top:12px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:8px;background:#fff;border-radius:999px;padding:7px 14px;font-size:12px;font-weight:600;color:#3e5243;box-shadow:0 4px 14px #0002;white-space:nowrap}
.route-map-status .skeleton{width:14px;height:14px;border-radius:50%}
.route-map-notice{position:absolute;left:12px;right:12px;top:56px;font-size:12px;background:#fff6e0;border:1px solid #ecd9a6;color:#7a5a12;border-radius:10px;padding:9px 12px;box-shadow:0 4px 14px #0001}
</style>
