<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { useForm } from '@inertiajs/vue3';
import { Bike, Bus, Car, CarTaxiFront, ChevronDown, Footprints, MapPin, Pencil, Plane, Plus, Route, Ship, TramFront, Wallet, X } from 'lucide-vue-next';
import ItineraryRouteMap, { type LegResult } from '@/components/ItineraryRouteMap.vue';
import LocationSearch, { type PickedPlace } from '@/components/LocationSearch.vue';
import { costRange, dayKey, dayLabel, orderedStops, samePlace, totalCost, transport, transportOptions, type ItineraryStop } from '@/lib/itinerary';
import { googleMapsUrl } from '@/lib/maps';

const props = defineProps<{ tripId: number; items: ItineraryStop[]; timezone: string; isOrganizer: boolean; googleMaps: { browserKey: string; mapId: string } }>();
const stops = computed(() => orderedStops(props.items));

// ---- Days and filtering -------------------------------------------------------------
const dayOf = (item: ItineraryStop) => dayKey(item.starts_at, props.timezone);
const days = computed(() => {
    const groups = new Map<string, { item: ItineraryStop; index: number }[]>();
    stops.value.forEach((item, index) => { const key = dayOf(item); groups.set(key, [...(groups.get(key) ?? []), { item, index }]); });
    return [...groups].map(([key, entries], i) => ({ key, number: i + 1, label: dayLabel(key), entries, budget: totalCost(entries.map(e => e.item)) }));
});
const selectedDay = ref<string | null>(null);
// Drop a day filter whose stops were all moved or deleted.
const activeDay = computed(() => (days.value.some(day => day.key === selectedDay.value) ? selectedDay.value : null));
const visibleDays = computed(() => (activeDay.value ? days.value.filter(day => day.key === activeDay.value) : days.value));
const budget = computed(() => totalCost(visibleDays.value.flatMap(day => day.entries.map(e => e.item))));

// ---- Journeys (shared with the map) ---------------------------------------------------
const selectedLeg = ref<number | null>(null);
const legResults = ref<Record<number, LegResult>>({});
const modeIcons: Record<string, unknown> = { flight: Plane, ferry: Ship, car: Car, van: Bus, taxi: CarTaxiFront, tricycle: CarTaxiFront, motorcycle: Bike, bicycle: Bike, walking: Footprints, transit: TramFront };
const legInfo = (index: number) => {
    const from = stops.value[index - 1]; const to = stops.value[index];
    const mode = transport(to.transport_mode);
    const same = samePlace(from, to);
    return { from, to, mode, same, icon: same ? MapPin : (modeIcons[mode?.value ?? ''] ?? Route), summary: legResults.value[to.id]?.summary };
};
const toggleLeg = (id: number) => { selectedLeg.value = selectedLeg.value === id ? null : id; };

// ---- Formatting ---------------------------------------------------------------------
const clock = (value: string) => new Intl.DateTimeFormat('en-PH', { hour: 'numeric', minute: '2-digit', timeZone: props.timezone }).format(new Date(value));
function until(item: ItineraryStop) {
    if (item.ends_at === item.starts_at) return '';
    const nextDay = dayKey(item.ends_at, props.timezone) !== dayOf(item);
    return `until ${clock(item.ends_at)}${nextDay ? ' next day' : ''}`;
}
const expanded = ref<Record<number, boolean>>({});
const hasDetails = (item: ItineraryStop) => !!(item.description || item.cost_note);

// ---- Add / edit form ------------------------------------------------------------------
const formOpen = ref(false);
const editingId = ref<number | null>(null);
const formElement = ref<HTMLElement>();
const form = useForm({ title: '', location: '', latitude: null as number | null, longitude: null as number | null, description: '', starts_at: '', ends_at: '', transport_mode: '', cost_min: '', cost_max: '', cost_note: '' });
const pinned = computed<PickedPlace | null>({
    get: () => (form.latitude != null && form.longitude != null ? { latitude: form.latitude, longitude: form.longitude } : null),
    set: (place) => { form.latitude = place?.latitude ?? null; form.longitude = place?.longitude ?? null; },
});
function localDate(value: string) {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: props.timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value));
    const part = (type: string) => parts.find(p => p.type === type)?.value;
    return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
}
const pesos = (minor?: number | null) => (minor == null ? '' : String(minor / 100));
const toMinor = (value: string | number) => (value === '' || value == null ? null : Math.round(Number(value) * 100));
function closeForm() { form.reset(); form.clearErrors(); editingId.value = null; formOpen.value = false; }
function openForm(item?: ItineraryStop) {
    form.reset(); form.clearErrors(); editingId.value = item?.id ?? null; formOpen.value = true;
    if (item) {
        Object.assign(form, {
            title: item.title, location: item.location ?? '', latitude: item.latitude ?? null, longitude: item.longitude ?? null, description: item.description ?? '',
            starts_at: localDate(item.starts_at), ends_at: localDate(item.ends_at), transport_mode: item.transport_mode ?? '',
            cost_min: pesos(item.cost_min_minor), cost_max: pesos(item.cost_max_minor), cost_note: item.cost_note ?? '',
        });
    }
    nextTick(() => formElement.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
}
function save() {
    const payload = form.transform(({ cost_min, cost_max, ...data }) => {
        const min = toMinor(cost_min); const max = toMinor(cost_max);
        // A single amount means an exact estimate.
        return { ...data, cost_min_minor: min ?? max, cost_max_minor: max ?? min };
    });
    const options = { preserveScroll: true, onSuccess: closeForm };
    if (editingId.value) payload.patch(`/trips/${props.tripId}/itinerary/${editingId.value}`, options);
    else payload.post(`/trips/${props.tripId}/itinerary`, options);
}
</script>

<template>
    <section class="itinerary">
        <header class="plan-head">
            <div>
                <h2>The plan</h2>
                <p class="muted">{{ days.length }} {{ days.length === 1 ? 'day' : 'days' }} · {{ stops.length }} stops · times in {{ timezone }}</p>
            </div>
            <div class="plan-actions">
                <div v-if="budget" class="budget-pill">
                    <Wallet :size="18" aria-hidden="true" />
                    <div><span>Est. per person{{ activeDay ? ' · this day' : '' }}</span><strong>{{ costRange(budget.min, budget.max) }}</strong></div>
                </div>
                <button v-if="isOrganizer && !formOpen" type="button" class="btn" @click="openForm()"><Plus :size="16" aria-hidden="true" /> Add a stop</button>
            </div>
        </header>

        <div v-if="days.length > 1" class="day-filter" role="group" aria-label="Filter the plan by day">
            <button type="button" :class="{ active: !activeDay }" :aria-pressed="!activeDay" @click="selectedDay = null">All days <span>{{ stops.length }}</span></button>
            <button v-for="day in days" :key="day.key" type="button" :class="{ active: activeDay === day.key }" :aria-pressed="activeDay === day.key" @click="selectedDay = day.key">Day {{ day.number }} · {{ day.label }} <span>{{ day.entries.length }}</span></button>
        </div>

        <form v-if="isOrganizer && formOpen" ref="formElement" class="card form-grid stop-form" @submit.prevent="save">
            <div class="row spread full"><h3>{{ editingId ? 'Edit stop' : 'Add a stop' }}</h3><button type="button" class="icon-close" aria-label="Close the form" @click="closeForm"><X :size="18" /></button></div>
            <label class="field">What's happening<input v-model="form.title" maxlength="200" required placeholder="e.g. Lunch at Tagbalayon" /></label>
            <div class="field"><label for="stop-location">Where</label><LocationSearch id="stop-location" v-model="form.location" v-model:pinned="pinned" :browser-key="googleMaps.browserKey" :maxlength="200" placeholder="Search a place, e.g. Siquijor Port" /></div>
            <label class="field">Starts ({{ timezone }})<input v-model="form.starts_at" type="datetime-local" required /></label>
            <label class="field">Ends ({{ timezone }})<input v-model="form.ends_at" type="datetime-local" required /></label>
            <label class="field">Getting there from the previous stop<select v-model="form.transport_mode"><option value="">Starting point / decide later</option><option v-for="option in transportOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
            <fieldset class="field cost-fields">
                <legend>Estimated cost per person (₱)</legend>
                <div class="cost-inputs"><input v-model="form.cost_min" type="number" min="0" step="1" inputmode="numeric" placeholder="From" aria-label="Minimum cost per person" /><span aria-hidden="true">–</span><input v-model="form.cost_max" type="number" min="0" step="1" inputmode="numeric" placeholder="To" aria-label="Maximum cost per person" /></div>
                <input v-model="form.cost_note" maxlength="255" placeholder="What it covers, e.g. Lunch, entrance fee" aria-label="What the cost covers" />
            </fieldset>
            <label class="field full">Notes<textarea v-model="form.description" maxlength="4000" rows="3" placeholder="Booking details, meeting point, or reminders" /></label>
            <ul v-if="Object.keys(form.errors).length" class="full form-errors" role="alert"><li v-for="(message, field) in form.errors" :key="field">{{ message }}</li></ul>
            <div class="row full"><button class="btn" :disabled="form.processing">{{ editingId ? 'Save stop' : 'Add stop' }}</button><button class="btn secondary" type="button" :disabled="form.processing" @click="closeForm">Cancel</button></div>
        </form>

        <div class="plan-layout">
            <aside class="plan-map">
                <ItineraryRouteMap v-model:selected="selectedLeg" :items="items" :browser-key="googleMaps.browserKey" :map-id="googleMaps.mapId" :timezone="timezone" :day="activeDay" @results="legResults = $event" />
            </aside>

            <div class="plan-timeline">
                <div v-if="!stops.length" class="card empty">
                    <h3>Start your plan</h3>
                    <p class="muted">Add your arrival point first, then the places you want to visit. Each stop shows on the map with its budget.</p>
                    <button v-if="isOrganizer" type="button" class="btn" @click="openForm()"><Plus :size="16" aria-hidden="true" /> Add the first stop</button>
                </div>

                <section v-for="day in visibleDays" :key="day.key" class="day" :aria-labelledby="`day-${day.key}`">
                    <header class="day-head">
                        <div><p class="eyebrow">Day {{ day.number }}</p><h3 :id="`day-${day.key}`">{{ day.label }}</h3></div>
                        <p class="day-meta">{{ day.entries.length }} stops<template v-if="day.budget"> · <strong>{{ costRange(day.budget.min, day.budget.max) }}</strong> per person</template></p>
                    </header>
                    <ol class="timeline">
                        <li v-for="{ item, index } in day.entries" :key="item.id">
                            <template v-if="index > 0">
                                <button
                                    v-for="leg in [legInfo(index)]"
                                    :key="leg.to.id"
                                    type="button"
                                    class="connector"
                                    :class="{ active: selectedLeg === item.id }"
                                    :aria-pressed="selectedLeg === item.id"
                                    :aria-label="`Show the journey from ${leg.from.title} to ${leg.to.title} on the map`"
                                    @click="toggleLeg(item.id)"
                                >
                                    <component :is="leg.icon" :size="15" aria-hidden="true" />
                                    <span>{{ leg.same ? 'Same spot' : (leg.mode?.label ?? 'Transport to be decided') }}</span>
                                    <span v-if="leg.summary && !leg.same" class="connector-summary">· {{ leg.summary }}</span>
                                </button>
                            </template>
                            <article class="stop" :class="{ focused: selectedLeg === item.id }">
                                <span class="stop-dot" aria-hidden="true">{{ index + 1 }}</span>
                                <div class="stop-body">
                                    <div class="stop-top">
                                        <p class="stop-time">{{ clock(item.starts_at) }} <span>{{ until(item) }}</span></p>
                                        <span v-if="costRange(item.cost_min_minor, item.cost_max_minor)" class="cost-chip">{{ costRange(item.cost_min_minor, item.cost_max_minor) }}<small>/person</small></span>
                                    </div>
                                    <h4>{{ item.title }}</h4>
                                    <a v-if="item.location" class="stop-place" :href="googleMapsUrl(item.location)" target="_blank" rel="noopener noreferrer"><MapPin :size="13" aria-hidden="true" /> {{ item.location }}<span class="sr-only"> (opens Google Maps)</span></a>
                                    <p v-else class="stop-place muted">Location to follow</p>
                                    <div v-if="hasDetails(item) || isOrganizer" class="stop-actions">
                                        <button v-if="hasDetails(item)" type="button" class="link-button" :aria-expanded="!!expanded[item.id]" @click="expanded[item.id] = !expanded[item.id]">
                                            Details <ChevronDown :size="14" :class="{ flipped: expanded[item.id] }" aria-hidden="true" />
                                        </button>
                                        <button v-if="isOrganizer" type="button" class="link-button" @click="openForm(item)"><Pencil :size="13" aria-hidden="true" /> Edit</button>
                                    </div>
                                    <div v-if="expanded[item.id]" class="stop-details">
                                        <p v-if="item.cost_note" class="cost-note"><Wallet :size="14" aria-hidden="true" /> {{ item.cost_note }}</p>
                                        <p v-if="item.description" class="stop-notes">{{ item.description }}</p>
                                    </div>
                                </div>
                            </article>
                        </li>
                    </ol>
                </section>
            </div>
        </div>
    </section>
</template>

<style scoped>
.plan-head{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;flex-wrap:wrap}
.plan-head .muted{margin-top:4px}
.plan-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.budget-pill{display:flex;align-items:center;gap:10px;background:#fff;border:1px solid #dfe7da;border-radius:14px;padding:9px 14px;color:#214e3e}
.budget-pill span{display:block;font-size:11px;color:#738178;font-weight:600}
.budget-pill strong{display:block;font-size:16px;letter-spacing:-.3px}
.day-filter{display:flex;gap:8px;overflow-x:auto;padding:4px 0 6px;margin-top:20px;scrollbar-width:thin}
.day-filter button{flex-shrink:0;display:inline-flex;align-items:center;gap:7px;border:1px solid #d5dfd5;background:#fff;color:#214e3e;border-radius:999px;padding:8px 14px;font-size:13px;font-weight:650;cursor:pointer;min-height:38px;transition:background .15s,border-color .15s}
.day-filter button:hover{border-color:#a9bd8e}
.day-filter button span{font-size:11px;background:#edf2e6;color:#526936;border-radius:999px;padding:1px 7px}
.day-filter button.active{background:#214e3e;border-color:#214e3e;color:#fff}
.day-filter button.active span{background:#ffffff26;color:#fff}
.stop-form{margin-top:18px}
.icon-close{display:grid;place-items:center;width:36px;height:36px;border-radius:10px;border:1px solid #d5dfd5;background:#fff;color:#214e3e;cursor:pointer}
.cost-fields{border:0;padding:0;margin:0;min-width:0}
.cost-fields legend{font-size:12px;font-weight:650;margin-bottom:7px}
.cost-inputs{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.form-errors{color:#ae4e36;font-size:13px}
.plan-layout{display:grid;gap:20px;margin-top:18px}
.plan-timeline{display:grid;gap:18px;min-width:0}
.day{background:#fff;border:1px solid #e0e7dc;border-radius:18px;padding:20px 20px 8px;box-shadow:0 3px 12px #233d2610}
.day-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px;flex-wrap:wrap;padding-bottom:14px;border-bottom:1px solid #edf1ea}
.day-head .eyebrow{margin-bottom:4px}
.travel-app .day-head h3{font-size:19px;letter-spacing:-.4px}
.day-meta{font-size:13px;color:#5f6f66}
.day-meta strong{color:#214e3e}
.timeline{list-style:none;margin:0;padding:6px 0 0;position:relative}
.timeline::before{content:"";position:absolute;left:15px;top:16px;bottom:24px;width:2px;background:#e4ebdf}
.connector{position:relative;display:flex;align-items:center;gap:7px;margin:2px 0 2px 34px;padding:6px 10px;border-radius:999px;border:1px dashed transparent;background:transparent;color:#5d7051;font-size:12px;font-weight:600;cursor:pointer;text-align:left;min-height:32px;max-width:calc(100% - 34px)}
.connector:hover{background:#f2f6ec;border-color:#cddab9}
.connector.active{background:#e8f0dc;border-color:#9fb87e;color:#214e3e}
.connector svg{flex-shrink:0}
.connector-summary{font-weight:500;color:#76846e;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.stop{position:relative;display:grid;grid-template-columns:32px 1fr;gap:12px;padding:10px 0 14px}
.stop-dot{position:relative;z-index:1;display:grid;place-items:center;width:32px;height:32px;border-radius:50%;background:#214e3e;color:#fff;font-size:12px;font-weight:800;border:3px solid #fff;box-shadow:0 0 0 1px #d5dfd5}
.stop.focused .stop-dot{background:#72883a}
.stop-body{min-width:0}
.stop-top{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}
.stop-time{font-size:12px;font-weight:700;color:#214e3e;letter-spacing:.2px}
.stop-time span{font-weight:500;color:#7a877f;margin-left:4px}
.cost-chip{display:inline-flex;align-items:baseline;gap:3px;background:#f3f7ec;border:1px solid #dbe6cc;color:#3f5a2a;border-radius:999px;padding:3px 10px;font-size:12px;font-weight:700;white-space:nowrap}
.cost-chip small{font-weight:500;color:#738178;font-size:11px}
.travel-app .stop h4{font-size:15px;font-weight:700;margin:4px 0;line-height:1.35;letter-spacing:-.2px;overflow-wrap:anywhere}
.stop-place{display:inline-flex;align-items:flex-start;gap:5px;font-size:12px;color:#5f6f66;line-height:1.5;overflow-wrap:anywhere}
a.stop-place:hover{color:#214e3e;text-decoration:underline;text-underline-offset:3px}
.stop-place svg{flex-shrink:0;margin-top:2px}
.stop-actions{display:flex;gap:6px;margin-top:6px;margin-left:-8px}
.link-button{display:inline-flex;align-items:center;gap:5px;padding:6px 8px;min-height:32px;border-radius:8px;border:0;background:transparent;color:#4f6f2c;font-size:12px;font-weight:650;cursor:pointer}
.link-button:hover{background:#f2f6ec}
.link-button .flipped{transform:rotate(180deg)}
.stop-details{margin-top:6px;background:#f8faf5;border:1px solid #e7ece3;border-radius:10px;padding:10px 12px;display:grid;gap:8px}
.cost-note{display:flex;gap:7px;font-size:12px;color:#3f5a2a;line-height:1.6}
.cost-note svg{flex-shrink:0;margin-top:3px}
.stop-notes{white-space:pre-wrap;font-size:13px;line-height:1.7;color:#3e4f47}
@media(min-width:1024px){
    .plan-layout{grid-template-columns:minmax(0,1fr) minmax(380px,44%);align-items:start}
    .plan-map{order:2;position:sticky;top:16px}
    .plan-timeline{order:1}
}
@media(max-width:640px){
    .plan-head{align-items:flex-start}
    .plan-actions{width:100%;justify-content:space-between}
    .day{padding:16px 14px 4px;border-radius:16px}
    .cost-inputs input{min-width:0}
}
</style>
