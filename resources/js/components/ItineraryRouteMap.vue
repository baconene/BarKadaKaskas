<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { directionsUrl, loadGoogleMaps, orderedStops, transport, type ItineraryStop } from '@/lib/itinerary';

const props = defineProps<{ items: ItineraryStop[]; browserKey: string; mapId: string }>();
const canvas = ref<HTMLElement>();
const stops = computed(() => orderedStops(props.items));
const legs = computed(() => stops.value.slice(1).map((to, i) => ({ from: stops.value[i], to, number: i + 1, mode: transport(to.transport_mode) })));
type LegResult = { summary: string; warnings: string[]; direct?: boolean };
const results = ref<Record<number, LegResult>>({});
const busy = ref(false);
const error = ref('');
const notice = ref('');
let generation = 0;
let polylines: google.maps.Polyline[] = [];
let markers: google.maps.marker.AdvancedMarkerElement[] = [];
// Addresses geocoded during this page view, so redraws don't repeat lookups.
const geocodeCache = new Map<string, google.maps.LatLngLiteral | null>();
function clear() { polylines.forEach(line => line.setMap(null)); markers.forEach(marker => marker.map = null); polylines = []; markers = []; }

const lineStyles: Record<string, { color: string; symbol: google.maps.Symbol; repeat: string }> = {
    flight: { color: '#6b7f3a', symbol: { path: 'M 0,-1 0,1', strokeOpacity: 1, scale: 3 }, repeat: '16px' },
    // 0 = google.maps.SymbolPath.CIRCLE (the enum isn't available until the Maps script loads).
    ferry: { color: '#2f7fa6', symbol: { path: 0 as google.maps.SymbolPath, fillOpacity: 1, strokeOpacity: 0, scale: 3 }, repeat: '12px' },
    fallback: { color: '#8a6d3b', symbol: { path: 'M 0,-1 0,1', strokeOpacity: 1, scale: 2 }, repeat: '10px' },
};

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
        const map = new maps.Map(canvas.value, { center: { lat: 12.88, lng: 121.77 }, zoom: 5, mapId: props.mapId || 'DEMO_MAP_ID', mapTypeControl: false });
        const bounds = new google.maps.LatLngBounds();
        const marked = new Set<number>();
        const geocoder = new geocoding.Geocoder();
        let geocodeDenied = false;

        const locate = async (address: string): Promise<google.maps.LatLngLiteral | null> => {
            if (geocodeCache.has(address)) return geocodeCache.get(address)!;
            try {
                const { results: found } = await geocoder.geocode({ address, region: 'ph' });
                const point = found[0]?.geometry.location.toJSON() ?? null;
                geocodeCache.set(address, point);
                return point;
            } catch (e) {
                if (String(e).includes('REQUEST_DENIED')) geocodeDenied = true;
                else geocodeCache.set(address, null);
                return null;
            }
        };
        const pin = (stop: ItineraryStop, position: google.maps.LatLngLiteral | google.maps.LatLng, number: number) => {
            bounds.extend(position);
            if (marked.has(stop.id)) return;
            const label = document.createElement('span'); label.textContent = String(number);
            label.style.cssText = 'background:#254d3d;color:white;border:2px solid white;border-radius:50%;padding:6px 10px;font-weight:bold;box-shadow:0 2px 6px #0003';
            markers.push(new markerLibrary.AdvancedMarkerElement({ map, position, title: `${number}. ${stop.title}`, content: label }));
            marked.add(stop.id);
        };
        const directLine = async (leg: (typeof legs.value)[number], style: keyof typeof lineStyles): Promise<string | null> => {
            const [from, to] = await Promise.all([locate(leg.from.location!), locate(leg.to.location!)]);
            if (version !== generation) return null;
            if (!from || !to) return geocodeDenied ? 'Google denied place lookup, so this journey can’t be placed on the map.' : 'One of these locations couldn’t be found on the map. Try a fuller address.';
            const { color, symbol, repeat } = lineStyles[style];
            const line = new maps.Polyline({ map, path: [from, to], geodesic: true, strokeOpacity: 0, icons: [{ icon: { ...symbol, strokeColor: color, fillColor: color }, offset: '0', repeat }] });
            polylines.push(line);
            pin(leg.from, from, leg.number); pin(leg.to, to, leg.number + 1);
            const km = geometry.spherical.computeDistanceBetween(from, to) / 1000;
            return `≈ ${km < 10 ? km.toFixed(1) : Math.round(km).toLocaleString()} km direct`;
        };

        for (const leg of legs.value) {
            if (version !== generation) return;
            if (!leg.from.location || !leg.to.location || !leg.mode) continue;
            if (!leg.mode.route) {
                const summary = await directLine(leg, leg.mode.value === 'flight' ? 'flight' : 'ferry');
                if (version !== generation) return;
                if (summary) results.value[leg.to.id] = { summary, warnings: [], direct: true };
                continue;
            }
            try {
                const response = await routes.Route.computeRoutes({ origin: leg.from.location, destination: leg.to.location, travelMode: leg.mode.route, fields: ['path', 'distanceMeters', 'durationMillis', 'warnings'] });
                if (version !== generation) return;
                const route = response.routes?.[0];
                if (!route?.path?.length) throw new Error('No route');
                const lines = route.createPolylines();
                lines.forEach(line => line.setMap(map)); polylines.push(...lines);
                route.path.forEach(point => bounds.extend(point));
                pin(leg.from, route.path[0], leg.number); pin(leg.to, route.path[route.path.length - 1], leg.number + 1);
                results.value[leg.to.id] = { summary: `${((route.distanceMeters ?? 0) / 1000).toFixed(1)} km · about ${Math.ceil((route.durationMillis ?? 0) / 60000)} min`, warnings: route.warnings ?? [] };
            } catch (e) {
                if (version !== generation) return;
                const message = String(e);
                if (message.includes('PERMISSION_DENIED')) { results.value[leg.to.id] = { summary: 'Google denied route access. Check that Routes API is enabled and permitted for this API key.', warnings: [] }; continue; }
                if (message.includes('RESOURCE_EXHAUSTED')) { results.value[leg.to.id] = { summary: 'Google routing quota has been reached. Try again later.', warnings: [] }; continue; }
                // No road/transit route (e.g. across water): show the journey as a direct line instead.
                const direct = await directLine(leg, 'fallback');
                if (version !== generation) return;
                results.value[leg.to.id] = direct
                    ? { summary: `${direct} · no ${leg.mode.label.toLowerCase()} route found, showing a straight line`, warnings: [], direct: true }
                    : { summary: 'Route unavailable. Check the locations or try another transport type.', warnings: [] };
            }
        }
        if (!bounds.isEmpty()) {
            if (marked.size === 1) { map.setCenter(bounds.getCenter()); map.setZoom(12); } else map.fitBounds(bounds, 45);
        }
        if (geocodeDenied) notice.value = 'Flight and ferry journeys need the Geocoding API. Enable it in Google Cloud and allow it on this API key.';
    } catch {
        if (version === generation) error.value = 'The map could not load. You can still open each journey in Google Maps.';
    } finally { if (version === generation) busy.value = false; }
}
watch([() => props.items, () => props.browserKey, canvas], draw, { deep: true, flush: 'post' });
onBeforeUnmount(() => { generation++; clear(); });
</script>

<template>
    <section class="card section-gap">
        <h3>Your route</h3>
        <p class="muted route-note">Stops follow your schedule. Transport is the journey from the previous stop; travel times are estimates. Flights and ferries are shown as direct lines.</p>
        <p v-if="stops.length < 2" class="muted section-gap">Add at least two stops with locations to plan your route.</p>
        <p v-else-if="!browserKey" class="muted section-gap">The interactive map is not enabled yet. Your route and transport choices are saved below; use the Google Maps links for directions.</p>
        <div v-if="browserKey && stops.length >= 2" ref="canvas" class="route-map" aria-label="Google Maps itinerary route" />
        <p v-if="busy" role="status" class="route-status">Drawing your route…</p>
        <p v-if="notice" role="status" class="route-notice">{{ notice }}</p>
        <p v-if="error" role="alert">{{ error }} <button class="btn secondary" @click="draw">Retry map</button></p>
        <ol class="route-legs">
            <li v-for="leg in legs" :key="leg.to.id">
                <strong>{{ leg.number }}. {{ leg.from.title }} → {{ leg.number + 1 }}. {{ leg.to.title }}</strong>
                <span>{{ leg.mode?.label ?? 'Choose transport' }}</span>
                <small v-if="!leg.from.location || !leg.to.location">Add a location to both stops to map this journey.</small>
                <template v-else-if="leg.mode">
                    <span v-if="results[leg.to.id]">{{ results[leg.to.id].summary }}</span>
                    <small v-for="warning in results[leg.to.id]?.warnings" :key="warning">{{ warning }}</small>
                    <a v-if="leg.mode.route" :href="directionsUrl(leg.from.location, leg.to.location, leg.mode.route)" target="_blank" rel="noopener noreferrer">Open journey in Google Maps ↗</a>
                </template>
            </li>
        </ol>
    </section>
</template>

<style scoped>
.route-map{height:400px;border-radius:12px;margin-top:18px;background:#edf2e8}.route-note{margin-top:8px;font-size:13px;line-height:1.7}.route-status{margin-top:10px;font-size:13px;color:#5f6f66}.route-notice{margin-top:10px;font-size:13px;background:#fff6e0;border:1px solid #ecd9a6;color:#7a5a12;border-radius:10px;padding:10px 12px}.route-legs{list-style:none;padding:0;margin-top:18px;display:grid;gap:14px}.route-legs li{display:flex;flex-direction:column;gap:6px;border-top:1px solid #e1e8db;padding-top:14px;font-size:13px}.route-legs a{color:#376245;text-decoration:underline;text-underline-offset:3px}.route-legs small{color:#76846e}@media(max-width:640px){.route-map{height:300px}}
</style>
