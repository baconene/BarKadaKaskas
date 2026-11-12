<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ChevronLeft, ChevronRight, ChevronUp, List, Navigation, Sparkles, Plus, Route as RouteIcon, Wallet, X } from 'lucide-vue-next';
import ItineraryRouteMap, { type LegResult } from '@/components/ItineraryRouteMap.vue';
import ActivitySheet from '@/components/trip/ActivitySheet.vue';
import DayPrep from '@/components/trip/DayPrep.vue';
import DailyRouteSummary from '@/components/trip/DailyRouteSummary.vue';
import DaySelector from '@/components/trip/DaySelector.vue';
import DayTimeline from '@/components/trip/DayTimeline.vue';
import StopFormSheet from '@/components/trip/StopFormSheet.vue';
import AiPlannerSheet from '@/components/trip/AiPlannerSheet.vue';
import { costRange, dayKey, directionsUrl, orderedStops, totalCost, transport, type ItineraryStop } from '@/lib/itinerary';
import { appFetch } from '@/lib/uploads';

export type PlanView = 'today' | 'itinerary' | 'map';
const props = defineProps<{ tripId: number; destination?: string; aiPlanner?: boolean; aiPrompt?: string | null; items: ItineraryStop[]; timezone: string; isOrganizer: boolean; googleMaps: { browserKey: string; mapId: string }; view: PlanView; visible: boolean }>();
const emit = defineEmits<{ navigate: [view: PlanView] }>();

// ---- Days -----------------------------------------------------------------------------
const stops = computed(() => orderedStops(props.items));
const dayOf = (item: ItineraryStop) => dayKey(item.starts_at, props.timezone);
const part = (key: string, options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-PH', { ...options, timeZone: 'UTC' }).format(new Date(`${key}T00:00:00Z`));
const days = computed(() => {
    const groups = new Map<string, { item: ItineraryStop; index: number }[]>();
    stops.value.forEach((item, index) => { const key = dayOf(item); groups.set(key, [...(groups.get(key) ?? []), { item, index }]); });
    return [...groups].map(([key, entries], i) => ({
        key, number: i + 1, entries, count: entries.length,
        weekday: part(key, { weekday: 'short' }), date: part(key, { month: 'short', day: 'numeric' }), long: part(key, { weekday: 'long', month: 'long', day: 'numeric' }),
        budget: totalCost(entries.map(e => e.item)),
        from: entries[0].item.location?.split(',')[0] ?? entries[0].item.title, to: entries[entries.length - 1].item.location?.split(',')[0] ?? '',
    }));
});
type Day = (typeof days.value)[number];
const dayByKey = (key: string | null) => days.value.find(day => day.key === key) ?? null;

// "Today": the current trip day, or the next one before the trip, or the last one after it.
const todayKey = new Intl.DateTimeFormat('en-CA', { timeZone: props.timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
const today = computed(() => {
    const list = days.value;
    if (!list.length) return null;
    const exact = list.find(day => day.key === todayKey);
    if (exact) return { day: exact, label: 'Today', note: `Day ${exact.number} of ${list.length}` };
    if (todayKey < list[0].key) {
        const daysAway = Math.round((Date.parse(list[0].key) - Date.parse(todayKey)) / 86400000);
        return { day: list[0], label: 'Up next', note: `Trip starts in ${daysAway} ${daysAway === 1 ? 'day' : 'days'}` };
    }
    const upcoming = list.find(day => day.key > todayKey);
    if (upcoming) return { day: upcoming, label: 'Up next', note: `Day ${upcoming.number} of ${list.length}` };
    return { day: list[list.length - 1], label: 'Last day', note: 'This trip has ended' };
});

// The day shown in Itinerary and Map; remembered per trip for this browser tab.
const DAY_KEY = `barkada.trip.${props.tripId}.day`;
const readDay = () => { try { return sessionStorage.getItem(DAY_KEY); } catch { return null; } };
const storedDay = readDay();
const selectedDay = ref<string | null>(storedDay === 'all' ? null : storedDay ?? today.value?.day.key ?? null);
watch(selectedDay, key => { try { sessionStorage.setItem(DAY_KEY, key ?? 'all'); } catch { /* storage unavailable */ } });
// Fall back if the remembered day no longer exists after edits.
watch(days, list => { if (selectedDay.value && !list.some(day => day.key === selectedDay.value)) selectedDay.value = list[0]?.key ?? null; }, { immediate: true });
const shownDay = computed<Day | null>(() => (props.view === 'today' ? today.value?.day ?? null : dayByKey(selectedDay.value)));
function stepDay(direction: 1 | -1) {
    const index = days.value.findIndex(day => day.key === selectedDay.value);
    const next = days.value[index + direction];
    if (next) selectedDay.value = next.key;
}

// ---- Journeys and the shared map ---------------------------------------------------------
const legs = computed(() => stops.value.slice(1).map((to, i) => ({ from: stops.value[i], to, number: i + 1, mode: transport(to.transport_mode) })));
const legsOn = (day: Day | null) => (day ? legs.value.filter(leg => dayOf(leg.to) === day.key) : legs.value);
const mapDay = computed(() => (props.view === 'today' ? today.value?.day.key ?? null : selectedDay.value));
const mapLegs = computed(() => legsOn(dayByKey(mapDay.value)));
const selectedLeg = ref<number | null>(null);
const focusedLeg = computed(() => legs.value.find(leg => leg.to.id === selectedLeg.value) ?? null);
const legResults = ref<Record<number, LegResult>>({});
const mapBusy = ref(false);
const isDesktop = ref(false);
let media: MediaQueryList | undefined;
const syncMedia = () => { isDesktop.value = !!media?.matches; };
onMounted(() => { media = window.matchMedia('(min-width: 1024px)'); syncMedia(); media.addEventListener('change', syncMedia); });
onBeforeUnmount(() => media?.removeEventListener('change', syncMedia));
const mapVisible = computed(() => props.visible && (props.view === 'map' || isDesktop.value));
const hasMap = computed(() => !!props.googleMaps.browserKey && stops.value.length >= 2);
watch(mapDay, () => { selectedLeg.value = null; });
// Previous / next journey from the focused card. On Today it stays within today; elsewhere it walks the whole
// trip and switches the day shown when the next journey is on another day.
const stepLegs = computed(() => (props.view === 'today' ? mapLegs.value : legs.value));
const focusedIndex = computed(() => stepLegs.value.findIndex(leg => leg.to.id === selectedLeg.value));
async function stepLeg(direction: 1 | -1) {
    const next = stepLegs.value[focusedIndex.value + direction];
    if (!next) return;
    const key = dayOf(next.to);
    if (props.view !== 'today' && selectedDay.value && selectedDay.value !== key) {
        selectedDay.value = key;
        await nextTick(); // the day change clears the focused journey first
    }
    selectedLeg.value = next.to.id;
}
function showJourneyList() { selectedLeg.value = null; if (props.view === 'map') sheetOpen.value = true; }
function focusLeg(id: number) {
    selectedLeg.value = selectedLeg.value === id ? null : id;
    // On phones the map has its own screen.
    if (selectedLeg.value && !isDesktop.value) emit('navigate', 'map');
}

// Map screen bottom sheet: peek (summary) or expanded (journey list); drag or tap the handle.
const sheetOpen = ref(false);
let dragStart = 0;
function dragBegin(event: PointerEvent) { dragStart = event.clientY; }
function dragEnd(event: PointerEvent) {
    const dy = event.clientY - dragStart;
    if (Math.abs(dy) < 8) sheetOpen.value = !sheetOpen.value; else sheetOpen.value = dy < 0;
}
watch(selectedLeg, id => { if (id && props.view === 'map') sheetOpen.value = false; });

// ---- Activity details, done state and the form -----------------------------------------------
const openItem = ref<ItineraryStop | null>(null);
const openIndex = computed(() => stops.value.findIndex(stop => stop.id === openItem.value?.id));
// "Done" is saved on the server so everyone on the trip sees the same ticks. A tap shows at once and
// rolls back if the save fails; fresh page data replaces these local changes.
const doneError = ref('');
const doneChanges = ref(new Map<number, boolean>());
watch(() => props.items, () => { doneChanges.value = new Map(); });
const done = computed(() => {
    const ids = new Set(props.items.filter(item => item.completed_at).map(item => item.id));
    doneChanges.value.forEach((value, id) => { if (value) ids.add(id); else ids.delete(id); });
    return ids;
});
async function toggleDone(id: number) {
    const value = !done.value.has(id);
    const previous = doneChanges.value.get(id) ?? null;
    const setChange = (next: boolean | null) => {
        const changes = new Map(doneChanges.value);
        if (next === null) changes.delete(id); else changes.set(id, next);
        doneChanges.value = changes;
    };
    setChange(value);
    doneError.value = '';
    const body = new FormData();
    body.append('_method', 'PATCH');
    body.append('done', value ? '1' : '0');
    try {
        const { data } = await appFetch<{ data: ItineraryStop }>(`/trips/${props.tripId}/itinerary/${id}/done`, { method: 'POST', body });
        setChange(!!data.completed_at);
    } catch (e) {
        setChange(previous);
        doneError.value = e instanceof Error ? e.message : 'Could not save. Try again.';
    }
}
const formOpen = ref<ItineraryStop | 'new' | null>(null);
// AI planner; opens straight away (and starts suggesting) when the trip was just created with an AI request.
const plannerOpen = ref(!!props.aiPrompt && props.isOrganizer && !!props.aiPlanner);
const canPlanWithAi = computed(() => props.isOrganizer && !!props.aiPlanner);
function editFromSheet(item: ItineraryStop) { openItem.value = null; formOpen.value = item; }
function showOnMap(id: number) { openItem.value = null; selectedLeg.value = id; if (!isDesktop.value) emit('navigate', 'map'); }
// Keep an open sheet in sync after the itinerary reloads.
watch(stops, list => { if (openItem.value) openItem.value = list.find(stop => stop.id === openItem.value!.id) ?? null; });

const tripBudget = computed(() => totalCost(stops.value));
const shownBudget = computed(() => (props.view === 'itinerary' && !selectedDay.value ? tripBudget.value : shownDay.value?.budget ?? null));
</script>

<template>
    <div class="plan" :class="[`view-${view}`, { 'map-on': mapVisible }]">
        <div class="plan-main">
            <!-- Empty trip -->
            <div v-if="!stops.length" class="card empty">
                <RouteIcon :size="34" class="empty-icon" aria-hidden="true" />
                <h2>Plan your first day</h2>
                <p class="muted">Add your arrival point, then the places you want to visit. Activities show on the map with their budget.</p>
                <div v-if="isOrganizer" class="empty-actions">
                    <button v-if="canPlanWithAi" type="button" class="btn" @click="plannerOpen = true"><Sparkles :size="16" aria-hidden="true" /> Plan it with AI</button>
                    <button type="button" class="btn" :class="{ secondary: canPlanWithAi }" @click="formOpen = 'new'"><Plus :size="16" aria-hidden="true" /> Add the first activity</button>
                </div>
            </div>

            <!-- Today -->
            <template v-else-if="view === 'today' && today">
                <header class="screen-head">
                    <div><p class="eyebrow">{{ today.label }} · {{ today.note }}</p><h2>{{ today.day.long }}</h2></div>
                    <span v-if="today.day.budget" class="budget-pill"><Wallet :size="15" aria-hidden="true" /> {{ costRange(today.day.budget.min, today.day.budget.max) }}<small>/person</small></span>
                </header>
                <DailyRouteSummary :title="today.label === 'Today' ? 'Today’s route' : `Day ${today.day.number} route`" :stops="today.day.entries.map(e => e.item)" :leg-ids="legsOn(today.day).map(l => l.to.id)" :results="legResults" :loading="mapBusy" :has-map="hasMap && !isDesktop" @view-route="emit('navigate', 'map')" />
                <div class="card timeline-card">
                    <DayTimeline :entries="today.day.entries" :stops="stops" :results="legResults" :timezone="timezone" :selected-leg="selectedLeg" :done="done" @open="openItem = $event" @focus-leg="focusLeg" />
                </div>
                <button type="button" class="see-all" @click="emit('navigate', 'itinerary')">See the full itinerary <ChevronRight :size="16" aria-hidden="true" /></button>
            </template>

            <!-- Itinerary -->
            <template v-else-if="view === 'itinerary'">
                <header class="screen-head">
                    <div><h2>Itinerary</h2><p class="muted">{{ days.length }} days · {{ stops.length }} activities</p></div>
                    <div class="head-actions">
                        <span v-if="shownBudget" class="budget-pill"><Wallet :size="15" aria-hidden="true" /> {{ costRange(shownBudget.min, shownBudget.max) }}<small>/person</small></span>
                        <button v-if="canPlanWithAi" type="button" class="btn secondary add-btn" @click="plannerOpen = true"><Sparkles :size="16" aria-hidden="true" /> <span>AI plan</span></button>
                        <button v-if="isOrganizer" type="button" class="btn add-btn" @click="formOpen = 'new'"><Plus :size="16" aria-hidden="true" /> <span>Add</span></button>
                    </div>
                </header>
                <DaySelector v-model="selectedDay" :days="days" allow-all :all-count="stops.length" label="Choose a day of the itinerary" />

                <div v-if="shownDay" class="day-view">
                    <div class="day-title"><h3>Day {{ shownDay.number }} · {{ shownDay.weekday }}, {{ shownDay.date }}</h3><span class="muted">{{ shownDay.count }} activities</span></div>
                    <DayPrep :trip-id="tripId" :date="shownDay.key" :signature="shownDay.entries.map(e => `${e.item.id}:${e.item.title}:${e.item.starts_at}`).join('|')" />
                    <DailyRouteSummary title="Route for the day" :stops="shownDay.entries.map(e => e.item)" :leg-ids="legsOn(shownDay).map(l => l.to.id)" :results="legResults" :loading="mapBusy" :has-map="hasMap && !isDesktop" @view-route="emit('navigate', 'map')" />
                    <div class="card timeline-card">
                        <DayTimeline :entries="shownDay.entries" :stops="stops" :results="legResults" :timezone="timezone" :selected-leg="selectedLeg" :done="done" @open="openItem = $event" @focus-leg="focusLeg" @swipe="stepDay" />
                    </div>
                    <div class="day-steps">
                        <button type="button" class="btn secondary" :disabled="shownDay.number === 1" @click="stepDay(-1)">Previous day</button>
                        <button type="button" class="btn secondary" :disabled="shownDay.number === days.length" @click="stepDay(1)">Next day</button>
                    </div>
                </div>

                <ul v-else class="day-summaries">
                    <li v-for="day in days" :key="day.key">
                        <button type="button" class="day-summary" @click="selectedDay = day.key">
                            <span class="day-badge"><small>Day</small>{{ day.number }}</span>
                            <span class="day-summary-body">
                                <strong>{{ day.weekday }}, {{ day.date }}</strong>
                                <span>{{ day.count }} activities<template v-if="day.from && day.to && day.from !== day.to"> · {{ day.from }} → {{ day.to }}</template></span>
                            </span>
                            <span v-if="day.budget" class="day-summary-cost">{{ costRange(day.budget.min, day.budget.max) }}</span>
                            <ChevronRight :size="18" aria-hidden="true" />
                        </button>
                    </li>
                </ul>
            </template>
        </div>

        <!-- The one shared map: its own screen on phones, beside the plan on desktop. -->
        <section v-if="stops.length" class="map-dock" :aria-hidden="!mapVisible" aria-label="Trip map">
            <div v-if="view === 'map'" class="map-top">
                <DaySelector v-model="selectedDay" :days="days" allow-all :all-count="stops.length" label="Choose which day to show on the map" />
            </div>
            <div class="map-frame">
                <ItineraryRouteMap v-model:selected="selectedLeg" :items="items" :browser-key="googleMaps.browserKey" :map-id="googleMaps.mapId" :timezone="timezone" :day="mapDay" :active="mapVisible" @results="legResults = $event" @busy="mapBusy = $event" />
            </div>

            <!-- Focused journey (all layouts) -->
            <div v-if="focusedLeg" class="leg-card" aria-live="polite">
                <div class="leg-head">
                    <p class="eyebrow" :title="`Journey ${focusedIndex + 1} of ${stepLegs.length} · ${focusedLeg.mode?.label ?? 'Transport to be decided'}`">Journey {{ focusedIndex + 1 }} of {{ stepLegs.length }} · {{ focusedLeg.mode?.label ?? 'Transport to be decided' }}</p>
                    <span class="leg-nav">
                        <button type="button" class="icon-btn" aria-label="Previous journey" :disabled="focusedIndex <= 0" @click="stepLeg(-1)"><ChevronLeft :size="18" /></button>
                        <button type="button" class="icon-btn" aria-label="Next journey" :disabled="focusedIndex < 0 || focusedIndex >= stepLegs.length - 1" @click="stepLeg(1)"><ChevronRight :size="18" /></button>
                        <button type="button" class="icon-btn" :aria-label="view === 'map' ? 'Show the journey list' : 'Close journey'" @click="showJourneyList"><List v-if="view === 'map'" :size="16" /><X v-else :size="16" /></button>
                    </span>
                </div>
                <h4>{{ focusedLeg.from.title }} → {{ focusedLeg.to.title }}</h4>
                <p class="muted">{{ legResults[focusedLeg.to.id]?.summary ?? 'Calculating…' }}</p>
                <div class="leg-card-actions">
                    <button type="button" class="btn secondary" @click="openItem = focusedLeg.to">Activity details</button>
                    <a v-if="focusedLeg.from.location && focusedLeg.to.location && focusedLeg.mode?.route && !legResults[focusedLeg.to.id]?.same" class="btn" :href="directionsUrl(focusedLeg.from.location, focusedLeg.to.location, focusedLeg.mode.route)" target="_blank" rel="noopener noreferrer"><Navigation :size="15" aria-hidden="true" /> Directions</a>
                </div>
            </div>

            <!-- Map screen: journey list in a draggable sheet -->
            <div v-else-if="view === 'map'" class="journey-sheet" :class="{ open: sheetOpen }">
                <button type="button" class="sheet-handle" :aria-expanded="sheetOpen" aria-controls="journey-list" @pointerdown="dragBegin" @pointerup="dragEnd" @keydown.enter.prevent="sheetOpen = !sheetOpen" @keydown.space.prevent="sheetOpen = !sheetOpen">
                    <span class="grabber" aria-hidden="true" />
                    <span class="sheet-summary"><strong>{{ mapDay ? `Day ${dayByKey(mapDay)?.number} journeys` : 'All journeys' }}</strong> · {{ mapLegs.length }}</span>
                    <ChevronUp :size="18" class="sheet-chevron" aria-hidden="true" />
                </button>
                <ol v-show="sheetOpen" id="journey-list" class="journey-list">
                    <li v-for="leg in mapLegs" :key="leg.to.id">
                        <button type="button" class="journey" @click="selectedLeg = leg.to.id">
                            <span class="journey-number">{{ leg.number + 1 }}</span>
                            <span class="journey-body"><strong>{{ leg.to.title }}</strong><span>{{ leg.mode?.label ?? 'Transport to be decided' }}<template v-if="legResults[leg.to.id]"> · {{ legResults[leg.to.id].summary }}</template></span></span>
                            <ChevronRight :size="16" aria-hidden="true" />
                        </button>
                    </li>
                    <li v-if="!mapLegs.length" class="muted journey-empty">No journeys on this day.</li>
                </ol>
            </div>
        </section>

        <ActivitySheet :trip-id="tripId" :item="openItem" :previous="openIndex > 0 ? stops[openIndex - 1] : null" :leg="openItem ? legResults[openItem.id] ?? null : null" :timezone="timezone" :is-organizer="isOrganizer"
            :done="!!openItem && done.has(openItem.id)" :done-error="doneError" @close="openItem = null" @edit="editFromSheet" @toggle-done="toggleDone" @show-on-map="showOnMap" />
        <AiPlannerSheet v-if="canPlanWithAi" :open="plannerOpen" :trip-id="tripId" :destination="destination ?? 'your trip'" :initial-prompt="aiPrompt" :auto-generate="!!aiPrompt" @close="plannerOpen = false" />
        <StopFormSheet v-if="isOrganizer" :open="formOpen" :trip-id="tripId" :timezone="timezone" :browser-key="googleMaps.browserKey" :default-day="view === 'itinerary' ? selectedDay : today?.day.key ?? null" @close="formOpen = null" />
    </div>
</template>

<style scoped>
.plan{display:grid;gap:16px}
.plan-main{display:grid;gap:14px;align-content:start;min-width:0}
.screen-head{display:flex;align-items:flex-end;justify-content:space-between;gap:12px;flex-wrap:wrap}
.screen-head .eyebrow{margin-bottom:4px}
.travel-app .screen-head h2{font-size:22px;letter-spacing:-.6px}
.head-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0;max-width:100%} /* wraps on phones instead of pushing the page wider than the screen */
.add-btn{min-height:44px}
.empty-actions{display:flex;gap:8px;justify-content:center;flex-wrap:wrap}
.budget-pill{display:inline-flex;align-items:center;gap:6px;font-size:13px;font-weight:750;color:#214e3e;background:#fff;border:1px solid #dfe7da;border-radius:999px;padding:7px 12px;white-space:nowrap}
.budget-pill small{font-weight:500;color:#738178;font-size:11px}
.timeline-card{padding:8px}
.empty-icon{margin:0 auto 12px;color:#839362}
.see-all{display:flex;align-items:center;justify-content:center;gap:6px;min-height:44px;border:0;background:transparent;color:#4f6f2c;font-weight:700;font-size:13px;cursor:pointer}
.day-view{display:grid;gap:12px;grid-template-columns:minmax(0,1fr)}
.day-title{display:flex;align-items:baseline;justify-content:space-between;gap:4px 10px;flex-wrap:wrap}
.travel-app .day-title h3{font-size:17px;letter-spacing:-.3px}
.day-steps{display:flex;justify-content:space-between;gap:10px}
.day-steps .btn{min-height:44px;flex:1}
.day-summaries{list-style:none;margin:0;padding:0;display:grid;gap:8px}
.day-summary{display:grid;grid-template-columns:auto minmax(0,1fr) auto auto;align-items:center;gap:12px;width:100%;min-height:64px;padding:10px 12px;border:1px solid #e0e7dc;border-radius:14px;background:#fff;text-align:left;color:inherit;font:inherit;cursor:pointer}
.day-summary:hover{border-color:#bcd0a4}
.day-badge{display:grid;justify-items:center;width:46px;height:46px;align-content:center;border-radius:12px;background:#214e3e;color:#fff;font-size:17px;font-weight:800;line-height:1}
.day-badge small{font-size:9px;text-transform:uppercase;letter-spacing:.6px;color:#cfe0a5;font-weight:700}
.day-summary-body{display:grid;min-width:0}
.day-summary-body strong{font-size:14px}
.day-summary-body span{font-size:12px;color:#6c7c72;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.day-summary-cost{font-size:12px;font-weight:700;color:#3f5a2a;white-space:nowrap}
/* Map dock */
.map-dock{position:relative;display:none;border-radius:18px;overflow:hidden;background:#e8eee2}
.map-on .map-dock{display:flex;flex-direction:column}
.map-frame{flex:1;min-height:0;border-radius:inherit}
.map-top{position:absolute;z-index:2;top:10px;left:10px;right:10px}
.map-top :deep(.day-option){box-shadow:0 2px 10px #0000001a;min-height:54px;min-width:56px;padding:6px 8px}
.leg-card,.journey-sheet{position:absolute;z-index:2;left:10px;right:10px;bottom:10px;background:#fffefb;border-radius:16px;box-shadow:0 8px 30px #1d332833}
.leg-card{padding:12px 14px;display:grid;gap:4px;grid-template-columns:minmax(0,1fr)} /* minmax(0) keeps long labels from widening the card past the screen */
.leg-card .eyebrow{margin:0}
.leg-card h4{font-size:15px;font-weight:750;line-height:1.3;overflow-wrap:anywhere}
.leg-card-actions{display:flex;gap:8px;margin-top:6px}
.leg-card-actions .btn{flex:1;min-height:44px;padding:10px 12px}
.leg-head{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:nowrap;min-width:0}
.leg-head .eyebrow{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.leg-nav{display:flex;gap:6px;flex-shrink:0}
.icon-btn:disabled{opacity:.4;cursor:default}
.icon-btn{display:grid;place-items:center;width:40px;height:40px;border-radius:10px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.sheet-handle{display:grid;grid-template-columns:1fr auto;align-items:center;gap:4px 8px;width:100%;padding:8px 16px 12px;border:0;background:transparent;color:inherit;font:inherit;cursor:grab;touch-action:none;text-align:left;min-height:56px}
.grabber{grid-column:1/-1;justify-self:center;width:40px;height:4px;border-radius:999px;background:#d3dccd;margin-bottom:4px}
.sheet-summary{font-size:13px;color:#5f6f66}
.sheet-summary strong{color:#172f2a}
.sheet-chevron{transition:transform .2s}
.journey-sheet.open .sheet-chevron{transform:rotate(180deg)}
.journey-list{list-style:none;margin:0;padding:0 8px 10px;max-height:min(46vh,420px);overflow-y:auto;overscroll-behavior:contain}
.journey{display:grid;grid-template-columns:30px minmax(0,1fr) auto;align-items:center;gap:10px;width:100%;min-height:52px;padding:8px;border:0;border-radius:12px;background:transparent;text-align:left;color:inherit;font:inherit;cursor:pointer}
.journey:hover{background:#f2f6ec}
.journey-number{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#214e3e;color:#fff;font-size:11px;font-weight:800}
.journey-body{display:grid;min-width:0}
.journey-body strong{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.journey-body span{font-size:12px;color:#6c7c72;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.journey-empty{padding:10px;font-size:13px}
/* Phones and tablets: the map screen fills the space between the top bar and the bottom navigation. */
@media(max-width:1023px){
    .view-map .plan-main{display:none}
    .view-map .map-dock{position:fixed;z-index:30;top:56px;left:0;right:0;bottom:calc(64px + env(safe-area-inset-bottom));border-radius:0}
}
/* Desktop: plan and map side by side (Today/Itinerary); the Map screen uses the full width. */
@media(min-width:1024px){
    .plan:not(.view-map){grid-template-columns:minmax(0,1fr) minmax(380px,44%);align-items:start}
    .plan:not(.view-map) .map-dock{position:sticky;top:76px;height:calc(100vh - 100px);min-height:460px}
    .view-map .plan-main{display:none}
    .view-map .map-dock{height:calc(100vh - 150px);min-height:520px}
    .journey-sheet,.leg-card{right:auto;width:380px}
    /* The side map can extend below the fold, so keep the focused journey at its top. */
    .plan:not(.view-map) .leg-card{top:10px;bottom:auto}
}
@media(prefers-reduced-motion:reduce){.sheet-chevron{transition:none}}
</style>
