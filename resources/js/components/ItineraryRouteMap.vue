<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { directionsUrl, loadGoogleMaps, orderedStops, transport, type ItineraryStop } from '@/lib/itinerary';

const props = defineProps<{ items: ItineraryStop[]; browserKey: string; mapId: string }>();
const canvas = ref<HTMLElement>();
const stops = computed(() => orderedStops(props.items));
const legs = computed(() => stops.value.slice(1).map((to, i) => ({ from: stops.value[i], to, number: i + 1, mode: transport(to.transport_mode) })));
const results = ref<Record<number, { summary: string; warnings: string[] }>>({});
const busy = ref(false);
const error = ref('');
let generation = 0;
let polylines: google.maps.Polyline[] = [];
let markers: google.maps.marker.AdvancedMarkerElement[] = [];
function clear() { polylines.forEach(line => line.setMap(null)); markers.forEach(marker => marker.map = null); polylines = []; markers = []; }

async function draw() {
    const version = ++generation;
    clear(); results.value = {}; error.value = ''; busy.value = false;
    if (!props.browserKey || !canvas.value || !legs.value.length) return;
    busy.value = true;
    try {
        await loadGoogleMaps(props.browserKey);
        const [maps, routes, markerLibrary] = await Promise.all([
            google.maps.importLibrary('maps') as Promise<google.maps.MapsLibrary>,
            google.maps.importLibrary('routes') as Promise<google.maps.RoutesLibrary>,
            google.maps.importLibrary('marker') as Promise<google.maps.MarkerLibrary>,
        ]);
        if (version !== generation || !canvas.value) return;
        const map = new maps.Map(canvas.value, { center: { lat: 12.88, lng: 121.77 }, zoom: 5, mapId: props.mapId || 'DEMO_MAP_ID', mapTypeControl: false });
        const bounds = new google.maps.LatLngBounds();
        const marked = new Set<number>();
        for (const leg of legs.value) {
            if (version !== generation) return;
            if (!leg.from.location || !leg.to.location || !leg.mode?.route) continue;
            try {
                const response = await routes.Route.computeRoutes({ origin: leg.from.location, destination: leg.to.location, travelMode: leg.mode.route, fields: ['path', 'distanceMeters', 'durationMillis', 'warnings'] });
                if (version !== generation) return;
                const route = response.routes?.[0];
                if (!route?.path?.length) throw new Error('No route');
                const lines = route.createPolylines();
                lines.forEach(line => line.setMap(map)); polylines.push(...lines);
                route.path.forEach(point => bounds.extend(point));
                for (const [stop, position, number] of [[leg.from, route.path[0], leg.number], [leg.to, route.path[route.path.length - 1], leg.number + 1]] as const) {
                    if (marked.has(stop.id)) continue;
                    const pin = document.createElement('span'); pin.textContent = String(number);
                    pin.style.cssText = 'background:#254d3d;color:white;border:2px solid white;border-radius:50%;padding:6px 10px;font-weight:bold';
                    markers.push(new markerLibrary.AdvancedMarkerElement({ map, position, title: `${number}. ${stop.title}`, content: pin }));
                    marked.add(stop.id);
                }
                map.fitBounds(bounds, 45);
                results.value[leg.to.id] = { summary: `${((route.distanceMeters ?? 0) / 1000).toFixed(1)} km · about ${Math.ceil((route.durationMillis ?? 0) / 60000)} min`, warnings: route.warnings ?? [] };
            } catch {
                if (version !== generation) return;
                results.value[leg.to.id] = { summary: 'Route unavailable. Check the locations or try another transport type.', warnings: [] };
            }
        }
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
        <p class="muted route-note">Stops follow your schedule. Transport is the journey from the previous stop; travel times are estimates. Public transit estimates use current departures.</p>
        <p v-if="stops.length < 2" class="muted section-gap">Add at least two stops with locations to plan your route.</p>
        <p v-else-if="!browserKey" class="muted section-gap">The interactive map is not enabled yet. Your route and transport choices are saved below; use the Google Maps links for directions.</p>
        <div v-if="browserKey && stops.length >= 2" ref="canvas" class="route-map" aria-label="Google Maps itinerary route" />
        <p v-if="busy" role="status">Drawing your route…</p>
        <p v-if="error" role="alert">{{ error }} <button class="btn secondary" @click="draw">Retry map</button></p>
        <ol class="route-legs">
            <li v-for="leg in legs" :key="leg.to.id">
                <strong>{{ leg.number }}. {{ leg.from.title }} → {{ leg.number + 1 }}. {{ leg.to.title }}</strong>
                <span>{{ leg.mode?.label ?? 'Choose transport' }}</span>
                <small v-if="!leg.from.location || !leg.to.location">Add a location to both stops to map this journey.</small>
                <template v-else-if="leg.mode?.route">
                    <span v-if="results[leg.to.id]">{{ results[leg.to.id].summary }}</span>
                    <small v-for="warning in results[leg.to.id]?.warnings" :key="warning">{{ warning }}</small>
                    <a :href="directionsUrl(leg.from.location, leg.to.location, leg.mode.route)" target="_blank" rel="noopener noreferrer">Open journey in Google Maps ↗</a>
                </template>
                <small v-else-if="leg.mode">Recorded in your itinerary. No Google route is drawn for this transport type.</small>
            </li>
        </ol>
    </section>
</template>

<style scoped>
.route-map{height:400px;border-radius:12px;margin-top:18px;background:#edf2e8}.route-note{margin-top:8px;font-size:13px;line-height:1.7}.route-legs{list-style:none;padding:0;margin-top:18px;display:grid;gap:14px}.route-legs li{display:flex;flex-direction:column;gap:6px;border-top:1px solid #e1e8db;padding-top:14px;font-size:13px}.route-legs a{color:#376245;text-decoration:underline;text-underline-offset:3px}.route-legs small{color:#76846e}@media(max-width:640px){.route-map{height:300px}}
</style>
