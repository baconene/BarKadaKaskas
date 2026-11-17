<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ChevronLeft, ChevronRight, LoaderCircle, Maximize2, Pause, Play, Printer, RotateCcw, Sparkles, X } from 'lucide-vue-next';
import { usePage } from '@inertiajs/vue3';
import { appFetch } from '@/lib/uploads';
import PresentationMap, { type MapPhoto } from '@/components/trip/PresentationMap.vue';

/**
 * The trip presentation: a slide deck that always fits the screen (nothing scrolls). Cover, the days at a glance,
 * then one slide per day with its live map (route, numbered stops, photos pinned), its stops (tap one to zoom the map
 * to it) and its photos (tap to view, then step through), then the AI's planned budget next to what was spent (tap a
 * day for its breakdown), and a closing slide. Arrow keys, swipes or the buttons move between slides; Play advances
 * on its own; Print saves every slide as a page (e.g. to PDF).
 */
type Stop = { n: number; time: string; title: string; location: string | null; transport: string | null; done: boolean; on_map: boolean; lat: number | null; lng: number | null };
type Photo = { url: string; caption: string | null; by: string | null; time: string };
type Day = { date: string; day_number: number; label: string; route: string; stops: Stop[]; pins?: MapPhoto[]; photos: Photo[]; photo_count: number; spent_minor: number };
type Deck = { title: string; destination: string; currency: string; dates: string; ended: boolean; cover_url: string | null; stats: { label: string; value: string }[]; spent_minor: number; days: Day[] };
type BudgetItem = { label: string; category: string; amount_minor: number };
type Budget = { available: boolean; failed: boolean; currency: string; travellers: number; total_estimate_minor: number; total_actual_minor: number; per_person_estimate_minor: number; per_person_actual_minor: number;
    days: { date: string; label: string; estimate_minor: number; actual_minor: number; items: BudgetItem[] }[]; categories: { category: string; estimate_minor: number }[]; actual_categories: { name: string; amount_minor: number }[]; notes: string[] };
type Slide = { kind: 'cover' | 'overview' | 'day' | 'budget' | 'end'; day?: Day };

const props = defineProps<{ tripId: number }>();
const emit = defineEmits<{ close: [] }>();
const deck = ref<Deck | null>(null);
const budget = ref<Budget | null>(null);
const budgetError = ref('');
const error = ref('');
const index = ref(0);
const root = ref<HTMLElement | null>(null);

const slides = computed<Slide[]>(() => {
    const d = deck.value; if (!d) return [];
    return [{ kind: 'cover' }, { kind: 'overview' }, ...d.days.map(day => ({ kind: 'day' as const, day })), { kind: 'budget' }, { kind: 'end' }];
});
const current = computed(() => slides.value[index.value]);
const money = (minor: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: deck.value?.currency ?? budget.value?.currency ?? 'PHP', maximumFractionDigits: 0 }).format(minor / 100);
const shortDate = (label: string) => label.split(' · ')[1] ?? label;
const categoryLabel: Record<string, string> = { food: 'Food', transport: 'Transport', activities: 'Activities', lodging: 'Lodging', other: 'Other' };
const googleMaps = computed(() => (usePage().props as { googleMaps?: { browserKey?: string; mapId?: string } }).googleMaps ?? {});

// Day slide: the stop the map is zoomed to, and the photo viewer (steps through the day's photos).
const focus = ref<number | null>(null);
const viewer = ref<{ photos: (Photo | MapPhoto)[]; at: number } | null>(null);
const viewing = computed(() => viewer.value?.photos[viewer.value.at] ?? null);
function openPhoto(photos: (Photo | MapPhoto)[], at: number) { pause(); viewer.value = { photos, at }; }
function openPin(day: Day, pin: MapPhoto) { const at = day.photos.findIndex(p => p.url === pin.url); openPhoto(at >= 0 ? day.photos : [pin], Math.max(0, at)); }
function step(by: number) { if (viewer.value) viewer.value.at = (viewer.value.at + by + viewer.value.photos.length) % viewer.value.photos.length; }

// Budget: the bar chart's scale and the day whose breakdown is shown.
const budgetMax = computed(() => Math.max(1, ...(budget.value?.days ?? []).flatMap(d => [d.estimate_minor, d.actual_minor])));
const budgetDay = ref<string | null>(null);
const pickedBudgetDay = computed(() => budget.value?.days.find(d => d.date === budgetDay.value) ?? null);
const planned = computed(() => !!budget.value?.available && !budget.value.failed);

// Play: moves to the next slide every few seconds, until the end or any interaction.
const playing = ref(false);
let timer: number | undefined;
function pause() { playing.value = false; window.clearInterval(timer); }
function play() {
    if (index.value >= slides.value.length - 1) index.value = 0;
    playing.value = true; window.clearInterval(timer);
    timer = window.setInterval(() => { if (index.value >= slides.value.length - 1) pause(); else index.value++; }, 7000);
}
function go(to: number, manual = true) { if (manual) pause(); index.value = Math.max(0, Math.min(slides.value.length - 1, to)); }
watch(index, () => { focus.value = null; budgetDay.value = null; });

function onKey(e: KeyboardEvent) {
    if (viewer.value) {
        if (e.key === 'Escape') viewer.value = null; else if (e.key === 'ArrowRight') step(1); else if (e.key === 'ArrowLeft') step(-1);
        return;
    }
    if (e.key === 'Escape') emit('close');
    else if (['ArrowRight', 'PageDown'].includes(e.key)) { e.preventDefault(); go(index.value + 1); }
    else if (['ArrowLeft', 'PageUp'].includes(e.key)) { e.preventDefault(); go(index.value - 1); }
    else if (e.key === ' ') { e.preventDefault(); if (playing.value) pause(); else play(); }
}
// One-finger swipes move between slides (or photos in the viewer); two fingers pan and zoom the map.
let touchX = 0, multiTouch = false;
const touchStart = (e: TouchEvent) => { multiTouch = e.touches.length > 1; touchX = e.touches[0]?.clientX ?? 0; };
const touchEnd = (e: TouchEvent) => {
    if (multiTouch || e.touches.length) { multiTouch = e.touches.length > 0; return; }
    const dx = (e.changedTouches[0]?.clientX ?? 0) - touchX;
    if (Math.abs(dx) < 50) return;
    if (viewer.value) step(dx < 0 ? 1 : -1); else go(index.value + (dx < 0 ? 1 : -1));
};
function fullscreen() { if (document.fullscreenElement) void document.exitFullscreen(); else void root.value?.requestFullscreen?.().catch(() => {}); }
function print() { pause(); window.print(); }

async function loadBudget() {
    budgetError.value = '';
    try { budget.value = (await appFetch<{ data: Budget }>(`/trips/${props.tripId}/presentation/budget`)).data; }
    catch (e) { budgetError.value = e instanceof Error ? e.message : 'Could not load the budget.'; }
}
onMounted(async () => {
    window.addEventListener('keydown', onKey);
    document.body.classList.add('presenting');
    try { deck.value = (await appFetch<{ data: Deck }>(`/trips/${props.tripId}/presentation`)).data; await nextTick(); root.value?.focus(); }
    catch (e) { error.value = e instanceof Error ? e.message : 'Could not load the presentation.'; }
    void loadBudget();
});
onBeforeUnmount(() => { pause(); window.removeEventListener('keydown', onKey); document.body.classList.remove('presenting'); if (document.fullscreenElement) void document.exitFullscreen(); });
</script>

<template>
    <Teleport to="body">
    <div ref="root" class="deck" role="dialog" aria-modal="true" aria-label="Trip presentation" tabindex="-1" @touchstart.passive="touchStart" @touchend="touchEnd">
        <header class="deck-bar">
            <div class="progress" aria-hidden="true"><i :style="{ width: slides.length ? ((index + 1) / slides.length) * 100 + '%' : '0' }" /></div>
            <strong class="deck-title">{{ deck?.title ?? 'Trip presentation' }}</strong>
            <button v-if="deck" type="button" class="deck-icon" :aria-label="playing ? 'Pause' : 'Play'" :title="playing ? 'Pause' : 'Play'" @click="playing ? pause() : play()"><Pause v-if="playing" :size="18" /><Play v-else :size="18" /></button>
            <button type="button" class="deck-icon hide-phone" aria-label="Print or save as PDF" title="Print or save as PDF" :disabled="!deck" @click="print"><Printer :size="18" /></button>
            <button type="button" class="deck-icon hide-phone" aria-label="Full screen" title="Full screen" @click="fullscreen"><Maximize2 :size="18" /></button>
            <button type="button" class="deck-icon" aria-label="Close presentation" @click="emit('close')"><X :size="20" /></button>
        </header>

        <div v-if="error" class="deck-state"><p>{{ error }}</p><button type="button" class="btn secondary" @click="emit('close')">Close</button></div>
        <div v-else-if="!deck" class="deck-state"><LoaderCircle class="spin" :size="28" /><p>Putting your trip together…</p></div>

        <main v-else class="deck-stage">
            <section v-for="(slide, i) in slides" :key="i" class="slide" :class="['slide-' + slide.kind, { current: i === index }]" :aria-hidden="i !== index">
                <!-- Cover -->
                <template v-if="slide.kind === 'cover'">
                    <img v-if="deck.cover_url" :src="deck.cover_url" alt="" class="cover-photo" />
                    <div class="cover-text">
                        <span class="eyebrow light">{{ deck.ended ? 'Trip recap' : deck.destination }}</span>
                        <h1 class="cover-title">{{ deck.title }}</h1>
                        <p class="cover-meta">{{ deck.dates }}</p>
                        <ul class="stat-chips"><li v-for="s in deck.stats" :key="s.label"><b>{{ s.value }}</b> {{ s.label }}</li></ul>
                        <button type="button" class="start" @click="go(1)">Start <ChevronRight :size="18" /></button>
                    </div>
                </template>

                <!-- Days at a glance -->
                <template v-else-if="slide.kind === 'overview'">
                    <div class="head"><h2 class="title">Day by day</h2><span v-if="deck.spent_minor" class="sub">{{ money(deck.spent_minor) }} spent</span></div>
                    <div class="day-grid" :style="{ '--rows': Math.ceil(deck.days.length / 2) }">
                        <button v-for="(day, d) in deck.days" :key="day.date" type="button" class="day-card" @click="go(2 + d)">
                            <img v-if="day.photos[0]" :src="day.photos[0].url" alt="" loading="lazy" />
                            <span class="day-card-text"><b>Day {{ day.day_number }}</b><small>{{ shortDate(day.label) }} · {{ day.stops.length }} {{ day.stops.length === 1 ? 'stop' : 'stops' }}</small></span>
                        </button>
                    </div>
                    <p v-if="!deck.days.length" class="muted center">Add itinerary stops or post photos and they’ll show up here.</p>
                </template>

                <!-- One day: map, stops, photos -->
                <template v-else-if="slide.kind === 'day' && slide.day">
                    <div class="head">
                        <span class="day-chip">Day {{ slide.day.day_number }}</span>
                        <h2 class="title">{{ shortDate(slide.day.label) }}</h2>
                        <span class="sub">{{ slide.day.stops.length }} {{ slide.day.stops.length === 1 ? 'stop' : 'stops' }} · {{ slide.day.photo_count }} {{ slide.day.photo_count === 1 ? 'photo' : 'photos' }}<template v-if="slide.day.spent_minor"> · {{ money(slide.day.spent_minor) }}</template></span>
                    </div>
                    <div class="day-body">
                        <div class="map-box">
                            <PresentationMap v-if="googleMaps.browserKey && i === index && (slide.day.stops.length || slide.day.pins?.length)" :stops="slide.day.stops" :pins="slide.day.pins ?? []" :browser-key="googleMaps.browserKey" :map-id="googleMaps.mapId ?? ''" :focus="focus" @open="openPin(slide.day, $event)" />
                            <p v-else class="map-empty">No places on the map for this day.</p>
                            <button v-if="focus != null" type="button" class="whole-day" @click="focus = null">Whole day</button>
                        </div>
                        <div class="day-side">
                            <ol v-if="slide.day.stops.length" class="stops">
                                <li v-for="stop in slide.day.stops" :key="stop.n">
                                    <button type="button" :class="{ on: focus === stop.n }" :disabled="!stop.on_map" @click="focus = focus === stop.n ? null : stop.n">
                                        <span class="stop-n">{{ stop.n }}</span>
                                        <span class="stop-text"><b>{{ stop.title }}</b><small>{{ stop.time }}</small></span>
                                    </button>
                                </li>
                            </ol>
                            <div v-if="slide.day.photos.length" class="thumbs" :class="'n' + Math.min(slide.day.photos.length, 6)">
                                <button v-for="(photo, p) in slide.day.photos" :key="p" type="button" :aria-label="photo.caption ?? 'Open photo'" @click="openPhoto(slide.day.photos, p)"><img :src="photo.url" alt="" loading="lazy" /></button>
                            </div>
                        </div>
                    </div>
                </template>

                <!-- Budget -->
                <template v-else-if="slide.kind === 'budget'">
                    <div class="head"><h2 class="title">Budget</h2><span class="sub"><Sparkles :size="13" aria-hidden="true" /> AI plan vs actual</span></div>
                    <div v-if="!budget && !budgetError" class="deck-state small"><LoaderCircle class="spin" :size="22" /><p>Estimating your budget…</p></div>
                    <div v-else-if="budgetError" class="deck-state small"><p>{{ budgetError }}</p><button type="button" class="btn secondary" @click="loadBudget">Try again</button></div>
                    <template v-else-if="budget">
                        <div class="tiles">
                            <div><span>Planned</span><b>{{ planned ? money(budget.total_estimate_minor) : '—' }}</b><small v-if="planned">{{ money(budget.per_person_estimate_minor) }} each</small><small v-else>{{ budget.available ? 'AI unavailable' : 'AI not set up' }} <button v-if="budget.failed" type="button" class="link" @click="loadBudget">Retry</button></small></div>
                            <div><span>Spent</span><b>{{ money(budget.total_actual_minor) }}</b><small>{{ money(budget.per_person_actual_minor) }} each</small></div>
                            <div v-if="planned && budget.total_estimate_minor"><span>{{ budget.total_actual_minor > budget.total_estimate_minor ? 'Over' : 'Under' }}</span><b :class="budget.total_actual_minor > budget.total_estimate_minor ? 'over' : 'under'">{{ money(Math.abs(budget.total_actual_minor - budget.total_estimate_minor)) }}</b><small>{{ Math.round((budget.total_actual_minor / budget.total_estimate_minor) * 100) }}% of plan</small></div>
                        </div>
                        <div class="chart" role="list">
                            <button v-for="d in budget.days" :key="d.date" type="button" role="listitem" class="col" :class="{ on: budgetDay === d.date }" :aria-label="`${d.label}: planned ${money(d.estimate_minor)}, spent ${money(d.actual_minor)}`" @click="budgetDay = budgetDay === d.date ? null : d.date">
                                <span class="bars"><i class="planned" :style="{ height: (d.estimate_minor / budgetMax) * 100 + '%' }" /><i class="actual" :style="{ height: (d.actual_minor / budgetMax) * 100 + '%' }" /></span>
                                <small>{{ d.label.split(' · ')[0] }}</small>
                            </button>
                        </div>
                        <div class="budget-foot">
                            <template v-if="pickedBudgetDay">
                                <b>{{ pickedBudgetDay.label }}</b> · planned {{ money(pickedBudgetDay.estimate_minor) }} · spent {{ money(pickedBudgetDay.actual_minor) }}
                                <span class="chips"><span v-for="item in pickedBudgetDay.items.slice(0, 5)" :key="item.label">{{ item.label }} {{ money(item.amount_minor) }}</span></span>
                            </template>
                            <template v-else>
                                <span class="legend"><i class="planned" /> Planned <i class="actual" /> Spent · tap a day</span>
                                <span v-if="budget.categories.length" class="chips"><span v-for="c in budget.categories.slice(0, 5)" :key="c.category">{{ categoryLabel[c.category] ?? c.category }} {{ money(c.estimate_minor) }}</span></span>
                                <span v-if="budget.notes[0]" class="note">{{ budget.notes[0] }}</span>
                            </template>
                        </div>
                    </template>
                </template>

                <!-- Closing -->
                <template v-else-if="slide.kind === 'end'">
                    <img v-if="deck.cover_url" :src="deck.cover_url" alt="" class="cover-photo dim" />
                    <div class="cover-text center">
                        <h1 class="cover-title">Salamat, barkada!</h1>
                        <p class="cover-meta">{{ deck.title }}</p>
                        <button type="button" class="start" @click="go(0)"><RotateCcw :size="16" /> Watch again</button>
                    </div>
                </template>
            </section>
        </main>

        <footer v-if="deck" class="deck-nav">
            <button type="button" class="deck-arrow" aria-label="Previous slide" :disabled="index === 0" @click="go(index - 1)"><ChevronLeft :size="22" /></button>
            <span class="deck-count">{{ current?.kind === 'day' ? `Day ${current.day?.day_number}` : current?.kind === 'budget' ? 'Budget' : current?.kind === 'overview' ? 'Overview' : '' }} <small>{{ index + 1 }} / {{ slides.length }}</small></span>
            <button type="button" class="deck-arrow" aria-label="Next slide" :disabled="index === slides.length - 1" @click="go(index + 1)"><ChevronRight :size="22" /></button>
        </footer>

        <div v-if="viewing && viewer" class="viewer" role="dialog" aria-label="Photo" @click.self="viewer = null">
            <button v-if="viewer.photos.length > 1" type="button" class="deck-arrow viewer-prev" aria-label="Previous photo" @click="step(-1)"><ChevronLeft :size="22" /></button>
            <figure>
                <img :src="viewing.url" :alt="viewing.caption ?? 'Photo'" />
                <figcaption><span v-if="viewing.caption">{{ viewing.caption }}</span><small>{{ [viewing.by, viewing.time].filter(Boolean).join(' · ') }}<template v-if="viewer.photos.length > 1"> · {{ viewer.at + 1 }}/{{ viewer.photos.length }}</template></small></figcaption>
            </figure>
            <button v-if="viewer.photos.length > 1" type="button" class="deck-arrow viewer-next" aria-label="Next photo" @click="step(1)"><ChevronRight :size="22" /></button>
            <button type="button" class="deck-icon viewer-close" aria-label="Close photo" @click="viewer = null"><X :size="20" /></button>
        </div>
    </div>
    </Teleport>
</template>

<style scoped>
.deck{position:fixed;inset:0;z-index:80;display:grid;grid-template-rows:auto minmax(0,1fr) auto;height:100dvh;background:#10231b;color:#f4f1e8;outline:none;overflow:hidden;font-family:Inter,ui-sans-serif,system-ui,sans-serif}
.deck *{box-sizing:border-box}
.deck h1,.deck h2,.deck p{margin:0;letter-spacing:normal}
.deck-bar{position:relative;display:flex;align-items:center;gap:6px;padding:10px 12px 8px}
.progress{position:absolute;left:0;right:0;top:0;height:3px;background:rgba(255,255,255,.12)}.progress i{display:block;height:100%;background:#f0a47e;transition:width .3s}
.deck-title{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:14px;font-weight:650}
.deck-icon,.deck-arrow{flex:none;display:grid;place-items:center;width:38px;height:38px;border-radius:50%;border:0;background:rgba(255,255,255,.1);color:inherit;cursor:pointer}
.deck-icon:disabled,.deck-arrow:disabled{opacity:.3;cursor:default}
.deck-state{display:grid;place-items:center;align-content:center;gap:12px;padding:24px;text-align:center;color:#d5dfd3;font-size:14px}
.deck-state.small{flex:1;color:#6c7c72}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.deck-stage{position:relative;min-height:0;padding:0 12px}
.slide{display:none;position:relative;height:100%;max-width:1280px;margin:0 auto;border-radius:18px;overflow:hidden;background:#f8f6ef;color:#1d2d24;padding:16px 18px}
.slide.current{display:flex;flex-direction:column;gap:12px;animation:fade .3s ease}
@keyframes fade{from{opacity:0}to{opacity:1}}
.head{flex:none;display:flex;align-items:baseline;flex-wrap:wrap;gap:4px 10px}
.title{font-size:clamp(17px,2.2vw,24px);font-weight:750;line-height:1.2}
.sub{display:inline-flex;align-items:center;gap:4px;font-size:13px;color:#6c7c72}
.muted{color:#6c7c72;font-size:14px}.center{text-align:center;margin:auto}
.eyebrow{font-size:11px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:#c2603a}.eyebrow.light{color:#ffc9a8}
/* Cover and closing */
.slide-cover,.slide-end{padding:0;background:#214e3e;color:#fff}
.cover-photo{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.cover-photo.dim{filter:brightness(.5)}
.cover-text{position:absolute;inset:auto 0 0;display:grid;gap:8px;justify-items:start;padding:28px 24px 24px;background:linear-gradient(transparent,rgba(9,26,19,.9) 45%)}
.cover-text.center{inset:0;align-content:center;justify-items:center;text-align:center;background:none}
.cover-title{font-size:clamp(24px,4.5vw,44px);font-weight:800;line-height:1.1;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.cover-meta{font-size:14px;opacity:.9}
.stat-chips{list-style:none;margin:2px 0 0;padding:0;display:flex;flex-wrap:wrap;gap:6px}
.stat-chips li{padding:5px 10px;border-radius:999px;background:rgba(255,255,255,.16);font-size:12px}
.start{display:inline-flex;align-items:center;gap:6px;margin-top:6px;padding:10px 18px;border:0;border-radius:999px;background:#fff;color:#214e3e;font-weight:700;font-size:14px;cursor:pointer}
/* Overview */
.day-grid{flex:1;min-height:0;display:grid;grid-template-columns:repeat(auto-fit,minmax(min(150px,45%),1fr));grid-auto-rows:minmax(0,1fr);gap:10px}
.day-card{position:relative;overflow:hidden;min-height:0;border:0;border-radius:14px;background:#214e3e;color:#fff;text-align:left;cursor:pointer;padding:0}
.day-card img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;filter:brightness(.7);transition:transform .3s}
.day-card:hover img{transform:scale(1.05)}
.day-card-text{position:absolute;inset:auto 0 0;display:grid;padding:8px 10px;background:linear-gradient(transparent,rgba(0,0,0,.6))}
.day-card-text b{font-size:15px}.day-card-text small{font-size:11px;opacity:.9;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
/* Day */
.day-chip{align-self:center;padding:4px 10px;border-radius:999px;background:#214e3e;color:#fff;font-size:12px;font-weight:700}
.day-body{flex:1;min-height:0;display:grid;grid-template-columns:minmax(0,1.7fr) minmax(240px,1fr);gap:14px}
.map-box{position:relative;min-height:0;border-radius:16px;overflow:hidden;background:#e7ece2}
.map-empty{display:grid;place-items:center;height:100%;padding:16px;text-align:center;color:#6c7c72;font-size:14px}
.whole-day{position:absolute;top:10px;left:10px;padding:7px 12px;border:0;border-radius:999px;background:#fff;color:#214e3e;font-weight:700;font-size:12px;box-shadow:0 2px 8px #0003;cursor:pointer}
.day-side{min-height:0;display:flex;flex-direction:column;gap:10px}
.stops{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:4px;min-height:0;overflow:hidden;flex:0 1 auto}
.stops button{display:flex;align-items:center;gap:8px;width:100%;padding:6px 8px;border:1px solid transparent;border-radius:10px;background:none;color:inherit;text-align:left;cursor:pointer}
.stops button:hover:not(:disabled),.stops button.on{background:#fff;border-color:#e3e9e1}
.stops button:disabled{cursor:default;opacity:.7}
.stop-n{flex:none;display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:#d9643a;color:#fff;font-weight:700;font-size:12px}
.stop-text{min-width:0;display:grid}.stop-text b,.stop-text small{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.stop-text b{font-size:14px;font-weight:650}.stop-text small{font-size:11px;color:#6c7c72}
.thumbs{flex:1 1 0;min-height:70px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));grid-auto-rows:minmax(0,1fr);gap:6px}
.thumbs.n1{grid-template-columns:1fr}.thumbs.n2,.thumbs.n4{grid-template-columns:repeat(2,minmax(0,1fr))}
.thumbs button{min-height:0;padding:0;border:0;border-radius:10px;overflow:hidden;background:#e7ece2;cursor:pointer}
.thumbs img{width:100%;height:100%;object-fit:cover;display:block;transition:transform .25s}.thumbs button:hover img{transform:scale(1.06)}
/* Budget */
.tiles{flex:none;display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:8px}
.tiles>div{display:grid;gap:1px;padding:10px 12px;border-radius:12px;background:#fff;border:1px solid #e3e9e1;min-width:0}
.tiles span{font-size:12px;color:#6c7c72}.tiles b{font-size:clamp(16px,2.2vw,22px)}.tiles small{font-size:11px;color:#6c7c72}
.over{color:#a6573e}.under{color:#3d7650}
.chart{flex:1;min-height:0;display:flex;align-items:stretch;gap:6px;padding:8px 4px 0;border-bottom:1px solid #e3e9e1}
.col{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px;padding:0;border:0;background:none;cursor:pointer;color:inherit}
.col .bars{flex:1;min-height:0;display:flex;align-items:flex-end;justify-content:center;gap:3px;border-radius:8px;padding:0 2px}
.col.on .bars,.col:hover .bars{background:#eef2ea}
.col i{display:block;width:min(18px,40%);min-height:2px;border-radius:5px 5px 0 0;transition:height .4s}
.col small{font-size:10px;color:#6c7c72;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-align:center}
.planned{background:#cfdcc4}.actual{background:#214e3e}
.budget-foot{flex:none;display:grid;gap:6px;font-size:13px;min-height:58px}
.legend{display:flex;align-items:center;gap:5px;color:#6c7c72;font-size:12px}.legend i{display:inline-block;width:10px;height:10px;border-radius:3px}.legend .actual{margin-left:6px}
.chips{display:flex;flex-wrap:wrap;gap:5px;max-height:52px;overflow:hidden}.chips span{padding:3px 9px;border-radius:999px;background:#fff;border:1px solid #e3e9e1;font-size:12px;white-space:nowrap}
.note{font-size:12px;color:#3f5a4b;display:-webkit-box;-webkit-line-clamp:1;-webkit-box-orient:vertical;overflow:hidden}
.link{border:0;background:none;color:#214e3e;text-decoration:underline;cursor:pointer;padding:0;font:inherit}
/* Nav and viewer */
.deck-nav{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 12px calc(8px + env(safe-area-inset-bottom))}
.deck-count{font-size:13px;font-weight:650;color:#e2eadf}.deck-count small{margin-left:6px;font-weight:400;color:#9fb3a2}
.viewer{position:absolute;inset:0;z-index:5;display:flex;align-items:center;justify-content:center;gap:10px;padding:56px 12px 24px;background:rgba(6,16,12,.92)}
.viewer figure{margin:0;min-width:0;max-width:min(1000px,100%);max-height:100%;display:flex;flex-direction:column;gap:8px}
.viewer img{min-height:0;max-width:100%;max-height:calc(100dvh - 160px);border-radius:12px;object-fit:contain}
.viewer figcaption{display:grid;color:#fff;font-size:14px}.viewer figcaption small{opacity:.8}
.viewer-close{position:absolute;top:12px;right:12px}
@media (max-width:760px){
    .slide{padding:12px;border-radius:14px}
    .slide.current{gap:10px}
    .day-body{grid-template-columns:1fr;grid-template-rows:minmax(0,1fr) auto}
    .day-side{flex-direction:column;gap:8px}
    .stops{flex-direction:row;gap:6px;overflow-x:auto;scrollbar-width:none}
    .stops::-webkit-scrollbar{display:none}
    .stops li{flex:none;max-width:70%}
    .stops button{border-color:#e3e9e1;background:#fff;padding:5px 10px 5px 5px}
    .stop-text small{display:none}
    .thumbs{flex:none;height:64px;grid-template-columns:repeat(6,minmax(0,1fr));grid-auto-rows:64px;overflow:hidden}
    .thumbs.n1,.thumbs.n2,.thumbs.n4{grid-template-columns:repeat(6,minmax(0,1fr))}
    .viewer-prev,.viewer-next{position:absolute;bottom:20px}.viewer-prev{left:calc(50% - 52px)}.viewer-next{right:calc(50% - 52px)}
    .hide-phone{display:none}
    .cover-text{padding:22px 18px 18px}
}
@media print{
    .deck{position:static;display:block;height:auto;background:#fff;color:#000}
    .deck-bar,.deck-nav{display:none}
    .deck-stage{padding:0}
    .slide{display:flex!important;flex-direction:column;gap:12px;height:100vh;border-radius:0;page-break-after:always;break-after:page;animation:none}
}
</style>

<style>
@media print{body.presenting>*:not(.deck){display:none!important}body.presenting{background:#fff}}
</style>
