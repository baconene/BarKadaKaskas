<script setup lang="ts">
import { computed, ref } from 'vue';
import { useForm } from '@inertiajs/vue3';
import ItineraryRouteMap from '@/components/ItineraryRouteMap.vue';
import LocationSearch, { type PickedPlace } from '@/components/LocationSearch.vue';
import { dayKey, dayLabel, orderedStops, transport, transportOptions, type ItineraryStop } from '@/lib/itinerary';
import { googleMapsUrl } from '@/lib/maps';

const props = defineProps<{ tripId: number; items: ItineraryStop[]; timezone: string; isOrganizer: boolean; googleMaps: { browserKey: string; mapId: string } }>();
const stops = computed(() => orderedStops(props.items));
const days = computed(() => {
    const counts = new Map<string, number>();
    for (const stop of stops.value) { const key = dayKey(stop.starts_at, props.timezone); counts.set(key, (counts.get(key) ?? 0) + 1); }
    return [...counts].map(([key, count]) => ({ key, label: dayLabel(key), count }));
});
const selectedDay = ref<string | null>(null);
// Drop a day filter whose stops were all moved or deleted.
const activeDay = computed(() => (days.value.some(day => day.key === selectedDay.value) ? selectedDay.value : null));
const onDay = (item: ItineraryStop) => !activeDay.value || dayKey(item.starts_at, props.timezone) === activeDay.value;
const editingId = ref<number | null>(null);
const formElement = ref<HTMLElement>();
const form = useForm({ title: '', location: '', latitude: null as number | null, longitude: null as number | null, description: '', starts_at: '', ends_at: '', transport_mode: '' });
const pinned = computed<PickedPlace | null>({
    get: () => (form.latitude != null && form.longitude != null ? { latitude: form.latitude, longitude: form.longitude } : null),
    set: (place) => { form.latitude = place?.latitude ?? null; form.longitude = place?.longitude ?? null; },
});
const displayDate = (value: string) => new Intl.DateTimeFormat('en-PH', { dateStyle: 'medium', timeStyle: 'short', timeZone: props.timezone }).format(new Date(value));
function localDate(value: string) {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: props.timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value));
    const part = (type: string) => parts.find(p => p.type === type)?.value;
    return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
}
function reset() { form.reset(); form.clearErrors(); editingId.value = null; }
function edit(item: ItineraryStop) {
    reset(); editingId.value = item.id;
    Object.assign(form, { title: item.title, location: item.location ?? '', latitude: item.latitude ?? null, longitude: item.longitude ?? null, description: item.description ?? '', starts_at: localDate(item.starts_at), ends_at: localDate(item.ends_at), transport_mode: item.transport_mode ?? '' });
    formElement.value?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
function save() {
    const options = { preserveScroll: true, onSuccess: reset };
    if (editingId.value) form.patch(`/trips/${props.tripId}/itinerary/${editingId.value}`, options);
    else form.post(`/trips/${props.tripId}/itinerary`, options);
}
</script>

<template>
    <section>
        <div class="row spread"><h2>The plan</h2><span class="muted">Times in {{ timezone }}</span></div>
        <div v-if="days.length > 1" class="day-filter section-gap" role="group" aria-label="Filter itinerary by day">
            <button type="button" :class="{ active: !activeDay }" :aria-pressed="!activeDay" @click="selectedDay = null">All days <span>{{ stops.length }}</span></button>
            <button v-for="day in days" :key="day.key" type="button" :class="{ active: activeDay === day.key }" :aria-pressed="activeDay === day.key" @click="selectedDay = day.key">{{ day.label }} <span>{{ day.count }}</span></button>
        </div>
        <ItineraryRouteMap :items="items" :browser-key="googleMaps.browserKey" :map-id="googleMaps.mapId" :timezone="timezone" :day="activeDay" />
        <p v-if="!stops.length" class="card empty section-gap">Start with your arrival point, then add the places you want to visit.</p>
        <template v-for="(item, index) in stops" :key="item.id">
        <article v-if="onDay(item)" class="card section-gap">
            <div class="row spread"><p class="eyebrow">Stop {{ index + 1 }} · {{ displayDate(item.starts_at) }}</p><button v-if="isOrganizer" class="btn secondary" @click="edit(item)">Edit stop</button></div>
            <h3>{{ item.title }}</h3>
            <p class="muted"><a v-if="item.location" :href="googleMapsUrl(item.location)" target="_blank" rel="noopener noreferrer">{{ item.location }} ↗</a><span v-else>Location to follow</span></p>
            <p v-if="index > 0" class="transport-label">From {{ stops[index - 1].title }}: {{ transport(item.transport_mode)?.label ?? 'Transport to be decided' }}</p>
            <p v-else class="transport-label">Starting point</p>
            <p v-if="item.description" class="stop-description">{{ item.description }}</p>
            <p class="muted">Until {{ displayDate(item.ends_at) }}</p>
        </article>
        </template>
        <form v-if="isOrganizer" ref="formElement" class="card form-grid section-gap" @submit.prevent="save">
            <h3 class="full">{{ editingId ? 'Edit itinerary stop' : 'Add to the itinerary' }}</h3>
            <label class="field">Activity<input v-model="form.title" maxlength="200" required /></label>
            <div class="field"><label for="stop-location">Location</label><LocationSearch id="stop-location" v-model="form.location" v-model:pinned="pinned" :browser-key="googleMaps.browserKey" :maxlength="200" placeholder="Search a place, e.g. Siquijor Port" /></div>
            <label class="field">Transport from previous stop<select v-model="form.transport_mode"><option value="">Starting point / decide later</option><option v-for="option in transportOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
            <label class="field">Notes<textarea v-model="form.description" maxlength="4000" placeholder="Booking details, meeting point, or transport notes" /></label>
            <label class="field">Starts ({{ timezone }})<input v-model="form.starts_at" type="datetime-local" required /></label>
            <label class="field">Ends ({{ timezone }})<input v-model="form.ends_at" type="datetime-local" required /></label>
            <ul v-if="Object.keys(form.errors).length" class="full form-errors" role="alert"><li v-for="(message, field) in form.errors" :key="field">{{ message }}</li></ul>
            <div class="row full"><button class="btn" :disabled="form.processing">{{ editingId ? 'Save stop' : 'Add activity' }}</button><button v-if="editingId" class="btn secondary" type="button" :disabled="form.processing" @click="reset">Cancel editing</button></div>
        </form>
    </section>
</template>

<style scoped>
.transport-label{font-size:13px;color:#426743;margin:12px 0}.stop-description{white-space:pre-wrap;font-size:13px;line-height:1.8;margin-bottom:12px}.muted a{text-decoration:underline;text-underline-offset:3px}.field small{font-weight:400;color:#71816f;line-height:1.6}.form-errors{color:#ae4e36;font-size:13px}
.day-filter{display:flex;gap:8px;overflow-x:auto;padding-bottom:4px;scrollbar-width:thin}
.day-filter button{flex-shrink:0;display:inline-flex;align-items:center;gap:7px;border:1px solid #d5dfd5;background:#fff;color:#214e3e;border-radius:999px;padding:8px 14px;font-size:13px;font-weight:650;cursor:pointer;transition:background .15s,border-color .15s}
.day-filter button:hover{border-color:#a9bd8e}
.day-filter button span{font-size:11px;background:#edf2e6;color:#526936;border-radius:999px;padding:1px 7px}
.day-filter button.active{background:#214e3e;border-color:#214e3e;color:#fff}
.day-filter button.active span{background:#ffffff26;color:#fff}
</style>
