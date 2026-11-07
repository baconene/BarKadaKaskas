<script setup lang="ts">
import { computed } from 'vue';
import { Map as MapIcon, Navigation } from 'lucide-vue-next';
import type { LegResult } from '@/components/ItineraryRouteMap.vue';
import { dayDirectionsUrl, type ItineraryStop } from '@/lib/itinerary';

/** One-line summary of a day's route: stops, distance and travel time, with map and navigation shortcuts. */
const props = defineProps<{ title: string; stops: ItineraryStop[]; legIds: number[]; results: Record<number, LegResult>; loading: boolean; hasMap: boolean }>();
const emit = defineEmits<{ viewRoute: [] }>();

const totals = computed(() => {
    let meters = 0; let seconds = 0; let direct = 0; let known = 0;
    for (const id of props.legIds) {
        const result = props.results[id];
        if (!result || result.meters == null) continue;
        known++;
        meters += result.meters;
        if (result.direct) direct++; else seconds += result.seconds ?? 0;
    }
    return { meters, seconds, direct, known };
});
const places = computed(() => new Set(props.stops.map(stop => stop.location).filter(Boolean)).size);
const distance = computed(() => (totals.value.meters < 10000 ? (totals.value.meters / 1000).toFixed(1) : Math.round(totals.value.meters / 1000).toLocaleString()));
const duration = computed(() => {
    const minutes = Math.round(totals.value.seconds / 60);
    return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
});
const navigateUrl = computed(() => dayDirectionsUrl(props.stops));
</script>

<template>
    <section class="route-summary" :aria-label="title">
        <div class="summary-text">
            <h3>{{ title }}</h3>
            <p v-if="loading && !totals.known" class="summary-line"><span class="skeleton" style="display:inline-block;width:180px;height:12px" aria-hidden="true" /><span class="sr-only">Calculating route…</span></p>
            <p v-else class="summary-line">
                {{ places }} {{ places === 1 ? 'place' : 'places' }}<template v-if="totals.known"> · {{ distance }} km<template v-if="totals.seconds"> · ~{{ duration }} on the road</template><template v-if="totals.direct"> · {{ totals.direct }} by air/sea</template></template>
            </p>
        </div>
        <div class="summary-actions">
            <button v-if="hasMap" type="button" class="btn secondary" @click="emit('viewRoute')"><MapIcon :size="15" aria-hidden="true" /> View route</button>
            <a v-if="navigateUrl" class="btn" :href="navigateUrl" target="_blank" rel="noopener noreferrer"><Navigation :size="15" aria-hidden="true" /> Navigate</a>
        </div>
    </section>
</template>

<style scoped>
.route-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;background:#fff;border:1px solid #e0e7dc;border-radius:16px;padding:14px 16px}
.summary-text{min-width:0}
.travel-app .route-summary h3{font-size:15px;font-weight:750;letter-spacing:-.2px}
.summary-line{margin-top:3px;font-size:12px;color:#5f6f66}
.summary-actions{display:flex;gap:8px}
.summary-actions .btn{min-height:44px;padding:10px 14px}
@media(max-width:420px){.summary-actions{width:100%}.summary-actions .btn{flex:1}}
</style>
