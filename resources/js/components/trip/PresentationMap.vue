<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { computeRoute } from '@/lib/routeCache';
import { guessTransport, loadGoogleMaps, transport, transportIcon } from '@/lib/itinerary';

/**
 * A day's map for the trip presentation, drawn like the trip's Map screen: the route between the day's stops (by
 * road for road trips, a dashed line for boats and planes), numbered stop pins, and the day's photos pinned where
 * they were taken (or at the stop they belong to). Tapping a photo pin shows it larger. Focusing a stop flies a
 * transport icon (car by default; plane, boat, on foot… from the stop's transport or a guess) along the leg to it.
 */
export type MapStop = { n: number; title: string; location: string | null; transport: string | null; lat: number | null; lng: number | null };
export type MapPhoto = { url: string; caption: string | null; by: string | null; time: string; lat: number | null; lng: number | null; stop: number | null };
/** [focus] is a stop number to zoom to (null shows the whole day). */
const props = defineProps<{ stops: MapStop[]; pins: MapPhoto[]; browserKey: string; mapId: string; focus?: number | null }>();
const emit = defineEmits<{ open: [photo: MapPhoto] }>();
const canvas = ref<HTMLElement>();
const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading');
let map: google.maps.Map | undefined;
const drawn: { setMap?: (m: google.maps.Map | null) => void; map?: google.maps.Map | null }[] = [];
let alive = true;
const geocoded = new Map<string, google.maps.LatLngLiteral | null>();
let stopPositions: (google.maps.LatLngLiteral | null)[] = [];
let wholeDay: google.maps.LatLngBounds | undefined;
// Each leg (index of the stop it arrives at): its path on the map and the transport used.
const legs = new Map<number, { path: google.maps.LatLngLiteral[]; mode: string }>();
let traveller: google.maps.marker.AdvancedMarkerElement | undefined;
let travellerIcon: HTMLElement | undefined;
let animation = 0;
/** Moves the transport icon along a path over [ms]. */
function travel(path: google.maps.LatLngLiteral[], mode: string, ms = 2600) {
    if (!map || !traveller || !travellerIcon || path.length < 2) return;
    travellerIcon.textContent = transportIcon(mode);
    traveller.map = map;
    const steps = [0];
    for (let i = 1; i < path.length; i++) steps.push(steps[i - 1]! + google.maps.geometry.spherical.computeDistanceBetween(path[i - 1]!, path[i]!));
    const total = steps[steps.length - 1]! || 1;
    const started = performance.now();
    cancelAnimationFrame(animation);
    const tick = (now: number) => {
        const t = Math.min(1, (now - started) / ms); const eased = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
        const at = eased * total;
        let i = steps.findIndex(s => s >= at); if (i <= 0) i = 1;
        const a = path[i - 1]!, b = path[i]!; const span = steps[i]! - steps[i - 1]! || 1; const f = (at - steps[i - 1]!) / span;
        traveller!.position = { lat: a.lat + (b.lat - a.lat) * f, lng: a.lng + (b.lng - a.lng) * f };
        if (t < 1 && alive) animation = requestAnimationFrame(tick);
    };
    animation = requestAnimationFrame(tick);
}
function frame() {
    if (!map) return;
    cancelAnimationFrame(animation);
    if (traveller) traveller.map = null;
    const index = props.focus != null ? props.stops.findIndex(s => s.n === props.focus) : -1;
    const leg = index > 0 ? legs.get(index) : undefined;
    if (leg) {
        // Frame the leg, then send the transport icon along it.
        const legBounds = new google.maps.LatLngBounds(); leg.path.forEach(p => legBounds.extend(p));
        map.fitBounds(legBounds, 70);
        travel(leg.path, leg.mode);
        return;
    }
    const at = index >= 0 ? stopPositions[index] : null;
    if (at) { map.panTo(at); map.setZoom(16); return; }
    if (!wholeDay || wholeDay.isEmpty()) return;
    if (wholeDay.getNorthEast().equals(wholeDay.getSouthWest())) { map.setCenter(wholeDay.getCenter()); map.setZoom(15); } else map.fitBounds(wholeDay, 50);
}
watch(() => props.focus, frame);

async function draw() {
    if (!props.browserKey || !canvas.value) { state.value = 'error'; return; }
    try {
        await loadGoogleMaps(props.browserKey);
        const [maps, routes, markerLibrary, geocoding] = await Promise.all([
            google.maps.importLibrary('maps') as Promise<google.maps.MapsLibrary>,
            google.maps.importLibrary('routes') as Promise<google.maps.RoutesLibrary>,
            google.maps.importLibrary('marker') as Promise<google.maps.MarkerLibrary>,
            google.maps.importLibrary('geocoding') as Promise<google.maps.GeocodingLibrary>,
            google.maps.importLibrary('geometry'),
        ]);
        if (!alive || !canvas.value) return;
        map = new maps.Map(canvas.value, { center: { lat: 12.88, lng: 121.77 }, zoom: 6, mapId: props.mapId || 'DEMO_MAP_ID', mapTypeControl: false, streetViewControl: false, fullscreenControl: false, zoomControl: false, gestureHandling: 'cooperative', clickableIcons: false });
        const geocoder = new geocoding.Geocoder();
        const locate = async (address: string) => {
            if (geocoded.has(address)) return geocoded.get(address)!;
            try { const { results } = await geocoder.geocode({ address }); const point = results[0]?.geometry.location.toJSON() ?? null; geocoded.set(address, point); return point; }
            catch { geocoded.set(address, null); return null; }
        };
        const positions = await Promise.all(props.stops.map(async s => (s.lat != null && s.lng != null ? { lat: s.lat, lng: s.lng } : s.location ? locate(s.location) : null)));
        if (!alive) return;
        const bounds = new google.maps.LatLngBounds();

        // Routes between consecutive stops.
        for (let i = 1; i < props.stops.length; i++) {
            const from = positions[i - 1], to = positions[i];
            if (!from || !to || (from.lat === to.lat && from.lng === to.lng)) continue;
            let kind = guessTransport(props.stops[i]!, google.maps.geometry.spherical.computeDistanceBetween(from, to));
            const mode = transport(kind === 'car' && !props.stops[i]!.transport ? 'car' : kind);
            let lines: google.maps.Polyline[] = [];
            let path: google.maps.LatLngLiteral[] = [from, to];
            if (mode?.route) {
                try {
                    // Shared with the Map screen and kept for the visit: revisiting a slide or replaying costs nothing.
                    const route = await computeRoute(routes, from, to, mode.route);
                    if (route?.path?.length) { lines = route.createPolylines(); route.path.forEach(p => bounds.extend(p)); path = route.path.map(p => { const q = p as unknown as { lat: number | (() => number); lng: number | (() => number) }; return { lat: typeof q.lat === 'function' ? q.lat() : q.lat, lng: typeof q.lng === 'function' ? q.lng() : q.lng }; }); }
                } catch { /* no road route: drawn as a dashed line below */ }
                // No road over a few km (an island hop): it was a boat.
                if (!lines.length && google.maps.geometry.spherical.computeDistanceBetween(from, to) > 3000) kind = 'ferry';
            }
            legs.set(i, { path, mode: kind });
            if (!lines.length) {
                const color = kind === 'ferry' ? '#2f7fa6' : '#6b7f3a';
                lines = [new maps.Polyline({ path: [from, to], geodesic: true, strokeOpacity: 0, icons: [{ icon: { path: 'M 0,-1 0,1', strokeOpacity: 1, scale: 3, strokeColor: color }, offset: '0', repeat: '16px' }] })];
            }
            if (!alive) return;
            lines.forEach(line => { line.setMap(map!); drawn.push(line); });
        }
        // Numbered stops.
        props.stops.forEach((stop, i) => {
            const at = positions[i]; if (!at) return;
            const label = document.createElement('span'); label.textContent = String(stop.n);
            label.style.cssText = 'background:#d9643a;color:white;border:2px solid white;border-radius:50%;width:30px;height:30px;display:grid;place-items:center;font:700 13px system-ui;box-shadow:0 2px 6px #0004';
            drawn.push(new markerLibrary.AdvancedMarkerElement({ map, position: at, title: `${stop.n}. ${stop.title}`, content: label, zIndex: 1 }));
            bounds.extend(at);
        });
        // Photo pins: where the photo was taken, else around the stop it belongs to (spread so they don't overlap).
        const perStop = new Map<number, number>();
        props.pins.forEach(photo => {
            let at: google.maps.LatLngLiteral | null = photo.lat != null && photo.lng != null ? { lat: photo.lat, lng: photo.lng } : null;
            if (!at && photo.stop != null) {
                const base = positions[props.stops.findIndex(s => s.n === photo.stop)];
                if (base) { const k = perStop.get(photo.stop) ?? 0; perStop.set(photo.stop, k + 1); const angle = (k * 2 * Math.PI) / 5 - Math.PI / 2; at = { lat: base.lat + 0.0009 * Math.sin(angle) + 0.0006, lng: base.lng + 0.0011 * Math.cos(angle) }; }
            }
            if (!at) return;
            const pin = document.createElement('button');
            pin.type = 'button'; pin.className = 'photo-pin'; pin.title = photo.caption ?? 'Photo';
            pin.style.cssText = 'position:relative;width:52px;height:52px;padding:0;border:3px solid #fff;border-radius:50%;background:#fff;box-shadow:0 4px 12px #0006;cursor:pointer;transform:translateY(-8px)';
            const img = document.createElement('img'); img.src = photo.url; img.alt = photo.caption ?? 'Photo'; img.loading = 'lazy';
            img.style.cssText = 'width:100%;height:100%;border-radius:50%;object-fit:cover;display:block';
            const tip = document.createElement('span');
            tip.style.cssText = 'position:absolute;left:50%;bottom:-12px;transform:translateX(-50%);border:7px solid transparent;border-top:8px solid #fff';
            pin.append(img, tip);
            const marker = new markerLibrary.AdvancedMarkerElement({ map, position: at, title: photo.caption ?? 'Photo', content: pin, zIndex: 2, gmpClickable: true });
            marker.addEventListener('gmp-click', () => emit('open', photo));
            drawn.push(marker);
            bounds.extend(at);
        });
        // The transport icon that travels along a leg when a stop is focused.
        travellerIcon = document.createElement('span');
        travellerIcon.style.cssText = 'display:grid;place-items:center;width:40px;height:40px;border-radius:50%;background:#fff;border:2px solid #214e3e;box-shadow:0 4px 12px #0005;font-size:22px;line-height:1';
        traveller = new markerLibrary.AdvancedMarkerElement({ position: positions.find(Boolean) ?? null, content: travellerIcon, zIndex: 3 });
        drawn.push(traveller);
        if (bounds.isEmpty()) { state.value = 'empty'; return; }
        stopPositions = positions; wholeDay = bounds;
        frame();
        state.value = 'ready';
    } catch {
        if (alive) state.value = 'error';
    }
}
onMounted(draw);
onBeforeUnmount(() => { alive = false; cancelAnimationFrame(animation); drawn.forEach(o => { if (o.setMap) o.setMap(null); else o.map = null; }); });
</script>

<template>
    <div class="pmap">
        <div ref="canvas" class="pmap-canvas" role="region" aria-label="Map of the day's route and photos" />
        <div v-if="state === 'loading'" class="pmap-note">Drawing the route…</div>
        <div v-else-if="state === 'empty'" class="pmap-cover">No places to show on the map for this day.</div>
        <div v-else-if="state === 'error'" class="pmap-cover">The map couldn’t load. Check the Google Maps key.</div>
    </div>
</template>

<style scoped>
.pmap{position:relative;border-radius:16px;overflow:hidden;background:#e7ece2;height:100%;min-height:0}
.pmap-canvas{position:absolute;inset:0}
.pmap-note{position:absolute;top:10px;left:50%;transform:translateX(-50%);background:#fff;border-radius:999px;padding:6px 12px;font-size:12px;font-weight:600;color:#3e5243;box-shadow:0 4px 14px #0002}
.pmap-cover{position:absolute;inset:0;display:grid;place-items:center;padding:20px;text-align:center;color:#6c7c72;background:#e7ece2}
</style>
