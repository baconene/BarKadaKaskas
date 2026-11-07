<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { Eye, EyeOff, X } from 'lucide-vue-next';
import { dayKey, directionsUrl, loadGoogleMaps, orderedStops, transport, type ItineraryStop } from '@/lib/itinerary';

const props = defineProps<{ items: ItineraryStop[]; browserKey: string; mapId: string; timezone: string; day: string | null }>();
const canvas = ref<HTMLElement>();
const stops = computed(() => orderedStops(props.items));
const legs = computed(() => stops.value.slice(1).map((to, i) => ({ from: stops.value[i], to, number: i + 1, mode: transport(to.transport_mode) })));
type Leg = (typeof legs.value)[number];
// A journey belongs to the day it arrives.
const dayLegs = computed(() => (props.day ? legs.value.filter(leg => dayKey(leg.to.starts_at, props.timezone) === props.day) : legs.value));
const selectedId = ref<number | null>(null);
const selected = computed(() => dayLegs.value.find(leg => leg.to.id === selectedId.value) ?? null);
const visibleLegs = computed(() => (selected.value ? [selected.value] : dayLegs.value));

type LegResult = { summary: string; warnings: string[]; direct?: boolean };
const results = ref<Record<number, LegResult>>({});
const busy = ref(false);
const error = ref('');
const notice = ref('');

const HIDDEN_KEY = 'barkada.itinerary.mapHidden';
const readHidden = () => { try { return localStorage.getItem(HIDDEN_KEY) === '1'; } catch { return false; } };
const mapHidden = ref(readHidden());
watch(mapHidden, hidden => {
    try { localStorage.setItem(HIDDEN_KEY, hidden ? '1' : '0'); } catch { /* storage unavailable */ }
    if (!hidden) nextTick(() => applyVisibility());
});

let generation = 0;
let map: google.maps.Map | undefined;
// Overlays are kept per journey so focusing or filtering only toggles them, never re-requests routes.
type Overlay = { lines: google.maps.Polyline[]; points: (google.maps.LatLngLiteral | google.maps.LatLng)[] };
const overlays = new Map<number, Overlay>();
const markers = new Map<number, google.maps.marker.AdvancedMarkerElement>();
// Reactive mirror of which journeys have overlays, for the template.
const mapped = ref<Record<number, true>>({});
// Addresses geocoded during this page view, so redraws don't repeat lookups.
const geocodeCache = new Map<string, google.maps.LatLngLiteral | null>();
// Once Google refuses geocoding (e.g. no billing), stop asking for the rest of the visit.
let geocodingBlocked = false;
function clear() {
    overlays.forEach(o => o.lines.forEach(line => line.setMap(null)));
    markers.forEach(marker => { marker.map = null; });
    overlays.clear(); markers.clear(); mapped.value = {};
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
    if (bounds.isEmpty() || mapHidden.value) return;
    if (shownStops.size === 1) { map.setCenter(bounds.getCenter()); map.setZoom(12); } else map.fitBounds(bounds, 45);
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
        if (!map || map.getDiv() !== canvas.value) map = new maps.Map(canvas.value, { center: { lat: 12.88, lng: 121.77 }, zoom: 5, mapId: props.mapId || 'DEMO_MAP_ID', mapTypeControl: false });
        const geocoder = new geocoding.Geocoder();
        let geocodeDenied = geocodingBlocked;

        const locate = async (address: string): Promise<google.maps.LatLngLiteral | null> => {
            if (geocodeCache.has(address)) return geocodeCache.get(address)!;
            if (geocodingBlocked) return null;
            try {
                const { results: found } = await geocoder.geocode({ address, region: 'ph' });
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
            label.style.cssText = 'background:#254d3d;color:white;border:2px solid white;border-radius:50%;padding:6px 10px;font-weight:bold;box-shadow:0 2px 6px #0003';
            markers.set(stop.id, new markerLibrary.AdvancedMarkerElement({ position, title: `${number}. ${stop.title}`, content: label }));
        };
        const directLine = async (leg: Leg, style: keyof typeof lineStyles): Promise<{ distance?: string; problem?: string } | null> => {
            const [from, to] = await Promise.all([position(leg.from), position(leg.to)]);
            if (version !== generation) return null;
            if (!from || !to) return { problem: geocodeDenied ? 'Google denied place lookup, so this journey can’t be placed on the map. Pick the locations from the search suggestions to pin them.' : 'One of these locations couldn’t be found on the map. Pick it from the search suggestions or try a fuller address.' };
            const { color, symbol, repeat } = lineStyles[style];
            const line = new maps.Polyline({ path: [from, to], geodesic: true, strokeOpacity: 0, icons: [{ icon: { ...symbol, strokeColor: color, fillColor: color }, offset: '0', repeat }] });
            overlays.set(leg.to.id, { lines: [line], points: [from, to] }); mapped.value[leg.to.id] = true;
            marker(leg.from, from, leg.number); marker(leg.to, to, leg.number + 1);
            const km = geometry.spherical.computeDistanceBetween(from, to) / 1000;
            return { distance: `≈ ${km < 10 ? km.toFixed(1) : Math.round(km).toLocaleString()} km direct` };
        };

        for (const leg of legs.value) {
            if (version !== generation) return;
            if (!leg.from.location || !leg.to.location || !leg.mode) continue;
            if (!leg.mode.route) {
                const direct = await directLine(leg, leg.mode.value === 'flight' ? 'flight' : 'ferry');
                if (version !== generation || !direct) return;
                results.value[leg.to.id] = { summary: direct.distance ?? direct.problem!, warnings: [], direct: !!direct.distance };
                applyVisibility();
                continue;
            }
            try {
                const response = await routes.Route.computeRoutes({ origin: routeEnd(leg.from), destination: routeEnd(leg.to), travelMode: leg.mode.route, fields: ['path', 'distanceMeters', 'durationMillis', 'warnings'] });
                if (version !== generation) return;
                const route = response.routes?.[0];
                if (!route?.path?.length) throw new Error('No route');
                overlays.set(leg.to.id, { lines: route.createPolylines(), points: route.path }); mapped.value[leg.to.id] = true;
                marker(leg.from, route.path[0], leg.number); marker(leg.to, route.path[route.path.length - 1], leg.number + 1);
                results.value[leg.to.id] = { summary: `${((route.distanceMeters ?? 0) / 1000).toFixed(1)} km · about ${Math.ceil((route.durationMillis ?? 0) / 60000)} min`, warnings: route.warnings ?? [] };
            } catch (e) {
                if (version !== generation) return;
                const message = String(e);
                if (message.includes('PERMISSION_DENIED')) { results.value[leg.to.id] = { summary: 'Google denied route access. Check that Routes API is enabled and permitted for this API key.', warnings: [] }; continue; }
                if (message.includes('RESOURCE_EXHAUSTED')) { results.value[leg.to.id] = { summary: 'Google routing quota has been reached. Try again later.', warnings: [] }; continue; }
                // No road/transit route (e.g. across water): show the journey as a direct line instead.
                const direct = await directLine(leg, 'fallback');
                if (version !== generation) return;
                results.value[leg.to.id] = direct?.distance
                    ? { summary: `${direct.distance} · no ${leg.mode.label.toLowerCase()} route found, showing a straight line`, warnings: [], direct: true }
                    : { summary: 'Route unavailable. Check the locations or try another transport type.', warnings: [] };
            }
            applyVisibility();
        }
        if (geocodeDenied) notice.value = 'Some stops were typed without picking a Google suggestion, so they can’t be pinned. Edit those stops and choose the place from the search suggestions. (Typed addresses also work once the Geocoding API is enabled with billing on your Google Cloud project.)';
    } catch {
        if (version === generation) error.value = 'The map could not load. You can still open each journey in Google Maps.';
    } finally { if (version === generation) busy.value = false; }
}

function toggle(leg: Leg) {
    selectedId.value = selectedId.value === leg.to.id ? null : leg.to.id;
    if (selectedId.value && mapHidden.value) mapHidden.value = false;
    if (selectedId.value) nextTick(() => canvas.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }));
}
const time = (value: string) => new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short', timeZone: props.timezone }).format(new Date(value));

watch([() => props.items, () => props.browserKey, canvas], draw, { deep: true, flush: 'post' });
watch(visibleLegs, () => applyVisibility());
onBeforeUnmount(() => { generation++; clear(); });
</script>

<template>
    <section class="card section-gap">
        <div class="row spread">
            <h3>Your route</h3>
            <button v-if="browserKey && stops.length >= 2" type="button" class="btn secondary map-toggle" :aria-pressed="mapHidden" @click="mapHidden = !mapHidden">
                <component :is="mapHidden ? Eye : EyeOff" :size="16" aria-hidden="true" /> {{ mapHidden ? 'Show map' : 'Hide map' }}
            </button>
        </div>
        <p class="muted route-note">Tap a journey to see only that route. Travel times are estimates; flights and ferries are shown as direct lines.</p>
        <p v-if="stops.length < 2" class="muted section-gap">Add at least two stops with locations to plan your route.</p>
        <p v-else-if="!browserKey" class="muted section-gap">The interactive map is not enabled yet. Your route and transport choices are saved below; use the Google Maps links for directions.</p>

        <div v-if="selected" class="leg-detail" aria-live="polite">
            <div class="row spread">
                <p class="eyebrow">Journey {{ selected.number }} · {{ selected.mode?.label ?? 'Transport to be decided' }}</p>
                <button type="button" class="btn secondary show-all" @click="selectedId = null"><X :size="15" aria-hidden="true" /> Show all routes</button>
            </div>
            <h4>{{ selected.from.title }} → {{ selected.to.title }}</h4>
            <dl>
                <div><dt>Leaves</dt><dd>{{ time(selected.from.ends_at) }}</dd></div>
                <div><dt>Arrives</dt><dd>{{ time(selected.to.starts_at) }}</dd></div>
                <div v-if="results[selected.to.id]"><dt>Distance</dt><dd>{{ results[selected.to.id].summary }}</dd></div>
            </dl>
            <p v-if="!mapped[selected.to.id] && !busy" class="muted">This journey isn’t on the map yet. Add pinned locations to both stops.</p>
        </div>

        <div v-if="browserKey && stops.length >= 2" v-show="!mapHidden" ref="canvas" class="route-map" aria-label="Google Maps itinerary route" />
        <p v-if="busy" role="status" class="route-status">Drawing your route…</p>
        <p v-if="notice" role="status" class="route-notice">{{ notice }}</p>
        <p v-if="error" role="alert">{{ error }} <button class="btn secondary" @click="draw">Retry map</button></p>
        <p v-if="day && !dayLegs.length && legs.length" class="muted section-gap">No journeys arrive on this day.</p>
        <ol class="route-legs">
            <li v-for="leg in dayLegs" :key="leg.to.id" :class="{ selected: selectedId === leg.to.id }">
                <button type="button" class="leg-button" :aria-pressed="selectedId === leg.to.id" @click="toggle(leg)">
                    <strong>{{ leg.number }}. {{ leg.from.title }} → {{ leg.number + 1 }}. {{ leg.to.title }}</strong>
                    <span>{{ leg.mode?.label ?? 'Choose transport' }}</span>
                    <small v-if="!leg.from.location || !leg.to.location">Add a location to both stops to map this journey.</small>
                    <span v-else-if="leg.mode && results[leg.to.id]">{{ results[leg.to.id].summary }}</span>
                    <small v-for="warning in results[leg.to.id]?.warnings" :key="warning">{{ warning }}</small>
                </button>
                <a v-if="leg.from.location && leg.to.location && leg.mode?.route" :href="directionsUrl(leg.from.location, leg.to.location, leg.mode.route)" target="_blank" rel="noopener noreferrer">Open journey in Google Maps ↗</a>
            </li>
        </ol>
    </section>
</template>

<style scoped>
.route-map{height:400px;border-radius:12px;margin-top:18px;background:#edf2e8}
.route-note{margin-top:8px;font-size:13px;line-height:1.7}
.map-toggle,.show-all{padding:8px 12px;font-size:12px}
.route-status{margin-top:10px;font-size:13px;color:#5f6f66}
.route-notice{margin-top:10px;font-size:13px;background:#fff6e0;border:1px solid #ecd9a6;color:#7a5a12;border-radius:10px;padding:10px 12px}
.leg-detail{margin-top:18px;border:1px solid #cfdcc4;background:#f4f8ee;border-radius:12px;padding:16px 18px}
.leg-detail .eyebrow{margin-bottom:0}
.leg-detail h4{font-size:17px;font-weight:700;margin:8px 0 12px;letter-spacing:-.3px}
.leg-detail dl{display:flex;flex-wrap:wrap;gap:10px 28px;font-size:13px}
.leg-detail dt{font-size:11px;text-transform:uppercase;letter-spacing:1px;color:#76846e;font-weight:700}
.leg-detail dd{margin-top:3px;color:#172f2a}
.route-legs{list-style:none;padding:0;margin-top:18px;display:grid;gap:6px}
.route-legs li{display:flex;flex-direction:column;gap:6px;border-top:1px solid #e1e8db;padding:8px 0 4px;font-size:13px}
.leg-button{display:flex;flex-direction:column;gap:6px;text-align:left;padding:10px 12px;margin:0 -12px;width:calc(100% + 24px);border-radius:10px;background:transparent;border:1px solid transparent;cursor:pointer;color:inherit;font:inherit;transition:background .15s,border-color .15s}
.leg-button:hover{background:#f4f7ef}
.route-legs li.selected .leg-button{background:#eef4e4;border-color:#bfd1a8}
.route-legs a{color:#376245;text-decoration:underline;text-underline-offset:3px}
.route-legs small{color:#76846e}
@media(max-width:640px){.route-map{height:300px}}
</style>
