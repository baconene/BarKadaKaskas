<script setup lang="ts">
import { BedDouble, Bike, Bus, Car, CarTaxiFront, CircleCheck, Footprints, Landmark, MapPin, Plane, Route, Ship, ShoppingBag, TramFront, Utensils, Waves } from 'lucide-vue-next';
import type { LegResult } from '@/components/ItineraryRouteMap.vue';
import { activityKind, costRange, samePlace, transport, type ActivityKind, type ItineraryStop } from '@/lib/itinerary';

const props = defineProps<{
    entries: { item: ItineraryStop; index: number }[];
    stops: ItineraryStop[];
    results: Record<number, LegResult>;
    timezone: string;
    selectedLeg: number | null;
    done: Set<number>;
}>();
const emit = defineEmits<{ open: [item: ItineraryStop]; focusLeg: [id: number]; swipe: [direction: 1 | -1] }>();

const kindIcons: Record<ActivityKind, unknown> = { food: Utensils, flight: Plane, ferry: Ship, stay: BedDouble, water: Waves, sight: Landmark, shop: ShoppingBag, place: MapPin };
const modeIcons: Record<string, unknown> = { flight: Plane, ferry: Ship, car: Car, van: Bus, taxi: CarTaxiFront, tricycle: CarTaxiFront, motorcycle: Bike, bicycle: Bike, walking: Footprints, transit: TramFront };
const clock = (value: string) => new Intl.DateTimeFormat('en-PH', { hour: 'numeric', minute: '2-digit', timeZone: props.timezone }).format(new Date(value));
function leg(index: number) {
    const from = props.stops[index - 1]; const to = props.stops[index];
    const mode = transport(to.transport_mode);
    const same = samePlace(from, to);
    const result = props.results[to.id];
    const travel = same ? 'Same spot' : [mode?.label ?? 'Transport to be decided', result && !result.direct ? result.summary.split(' · ').pop() : result?.summary].filter(Boolean).join(' · ');
    return { from, to, icon: same ? MapPin : (modeIcons[mode?.value ?? ''] ?? Route), travel };
}

// Horizontal swipe on the timeline switches day (touch only; vertical scrolling is unaffected).
let startX = 0; let startY = 0;
function touchStart(event: TouchEvent) { startX = event.touches[0].clientX; startY = event.touches[0].clientY; }
function touchEnd(event: TouchEvent) {
    const dx = event.changedTouches[0].clientX - startX; const dy = event.changedTouches[0].clientY - startY;
    if (Math.abs(dx) > 70 && Math.abs(dy) < 45) emit('swipe', dx < 0 ? 1 : -1);
}
</script>

<template>
    <ol class="day-timeline" @touchstart.passive="touchStart" @touchend.passive="touchEnd">
        <li v-for="{ item, index } in entries" :key="item.id">
            <template v-if="index > 0">
                <button v-for="l in [leg(index)]" :key="l.to.id" type="button" class="travel" :class="{ active: selectedLeg === item.id }" :aria-pressed="selectedLeg === item.id" :aria-label="`Journey to ${item.title}: ${l.travel}. Show on map`" @click="emit('focusLeg', item.id)">
                    <component :is="l.icon" :size="13" aria-hidden="true" /><span>{{ l.travel }}</span>
                </button>
            </template>
            <button type="button" class="activity" :class="{ done: done.has(item.id) }" :aria-label="`${clock(item.starts_at)}, ${item.title}. Open details`" @click="emit('open', item)">
                <span class="time">{{ clock(item.starts_at) }}</span>
                <span class="dot" aria-hidden="true">
                    <CircleCheck v-if="done.has(item.id)" :size="16" />
                    <component :is="kindIcons[activityKind(item.title)]" v-else :size="15" />
                </span>
                <span class="body">
                    <span class="title">{{ item.title }}</span>
                    <span v-if="item.location" class="place">{{ item.location.split(',')[0] }}</span>
                </span>
                <span v-if="costRange(item.cost_min_minor, item.cost_max_minor)" class="cost">{{ costRange(item.cost_min_minor, item.cost_max_minor) }}</span>
            </button>
        </li>
    </ol>
</template>

<style scoped>
.day-timeline{list-style:none;margin:0;padding:0;position:relative}
.day-timeline::before{content:"";position:absolute;left:83px;top:22px;bottom:22px;width:2px;background:#e3eadd}
.activity{position:relative;display:grid;grid-template-columns:62px 32px minmax(0,1fr) auto;align-items:center;gap:8px;width:100%;min-height:56px;padding:8px 8px 8px 4px;border:0;border-radius:12px;background:transparent;text-align:left;color:inherit;font:inherit;cursor:pointer;transition:background .15s}
.activity:hover{background:#f3f6ef}
.activity:focus-visible,.travel:focus-visible{outline:3px solid #9cb865;outline-offset:1px}
.time{font-size:12px;font-weight:700;color:#214e3e;text-align:right;white-space:nowrap}
.dot{position:relative;z-index:1;display:grid;place-items:center;width:32px;height:32px;border-radius:50%;background:#eef3e7;color:#214e3e;border:2px solid #fff;box-shadow:0 0 0 1px #d6e0cf}
.body{display:grid;min-width:0}
.title{font-size:14px;font-weight:700;line-height:1.3;color:#172f2a;overflow-wrap:anywhere}
.place{font-size:12px;color:#6c7c72;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cost{font-size:11px;font-weight:700;color:#3f5a2a;background:#f3f7ec;border:1px solid #dbe6cc;border-radius:999px;padding:3px 8px;white-space:nowrap}
.activity.done .title{color:#7b8a80;text-decoration:line-through;text-decoration-color:#b8c4b0}
.activity.done .dot{background:#e0f3e8;color:#276c47}
.travel{display:flex;align-items:center;gap:6px;margin:0 0 0 104px;padding:4px 10px;min-height:30px;max-width:calc(100% - 104px);border-radius:999px;border:1px dashed transparent;background:transparent;color:#6a7d5e;font-size:11px;font-weight:600;cursor:pointer;text-align:left}
.travel span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.travel svg{flex-shrink:0}
.travel:hover{background:#f2f6ec;border-color:#cddab9}
.travel.active{background:#e8f0dc;border-color:#9fb87e;color:#214e3e}
@media(max-width:380px){
    .day-timeline::before{left:71px}
    .activity{grid-template-columns:52px 30px minmax(0,1fr) auto;gap:7px}
    .dot{width:30px;height:30px}
    .travel{margin-left:90px;max-width:calc(100% - 90px)}
    .cost{font-size:10px;padding:2px 6px}
}
@media(prefers-reduced-motion:reduce){.activity{transition:none}}
</style>
