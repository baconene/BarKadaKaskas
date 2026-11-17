<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { ChevronLeft, ChevronRight, LoaderCircle, Maximize2, Printer, Sparkles, X } from 'lucide-vue-next';
import { usePage } from '@inertiajs/vue3';
import { appFetch } from '@/lib/uploads';
import PresentationMap, { type MapPhoto } from '@/components/trip/PresentationMap.vue';

/**
 * The trip presentation: a slide deck built from the trip. Cover, overview, then for each day its map (the route
 * between the numbered stops, with some of the day's photos pinned where they were taken) and its best photos, then
 * the AI's planned budget next to what was actually spent, and a closing slide. Arrow keys, swipes or the buttons move
 * between slides; Print saves every slide as a page (e.g. to PDF).
 */
type Point = { x: number; y: number };
type Stop = { n: number; time: string; title: string; location: string | null; transport: string | null; done: boolean; on_map: boolean; lat: number | null; lng: number | null };
type Pin = Point & { url: string; caption: string | null; by: string | null };
type Photo = { url: string; caption: string | null; by: string | null; time: string };
type Day = { date: string; day_number: number; label: string; route: string; stops: Stop[]; pins?: MapPhoto[]; map: { url: string; stops: (Point & { n: number; title: string })[]; pins: Pin[] } | null; photos: Photo[]; photo_count: number; spent_minor: number };
type Deck = { title: string; destination: string; currency: string; dates: string; ended: boolean; cover_url: string | null; stats: { label: string; value: string }[]; spent_minor: number; days: Day[] };
type BudgetItem = { label: string; category: string; amount_minor: number };
type Budget = { available: boolean; failed: boolean; currency: string; travellers: number; total_estimate_minor: number; total_actual_minor: number; per_person_estimate_minor: number; per_person_actual_minor: number;
    days: { date: string; label: string; estimate_minor: number; actual_minor: number; items: BudgetItem[] }[]; categories: { category: string; estimate_minor: number }[]; actual_categories: { name: string; amount_minor: number }[]; notes: string[] };
type Slide = { kind: 'cover' | 'overview' | 'map' | 'photos' | 'budget' | 'end'; day?: Day };

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
    const list: Slide[] = [{ kind: 'cover' }, { kind: 'overview' }];
    for (const day of d.days) {
        list.push({ kind: 'map', day });
        if (day.photos.length) list.push({ kind: 'photos', day });
    }
    list.push({ kind: 'budget' }, { kind: 'end' });
    return list;
});
const money = (minor: number) => new Intl.NumberFormat('en-PH', { style: 'currency', currency: deck.value?.currency ?? budget.value?.currency ?? 'PHP', maximumFractionDigits: 0 }).format(minor / 100);
const categoryLabel: Record<string, string> = { food: 'Food & drinks', transport: 'Getting around', activities: 'Activities & fees', lodging: 'Lodging', other: 'Other' };
const budgetMax = computed(() => Math.max(1, ...(budget.value?.days ?? []).flatMap(d => [d.estimate_minor, d.actual_minor])));
const categoryMax = computed(() => Math.max(1, ...(budget.value?.categories ?? []).map(c => c.estimate_minor)));
const onMap = (p: Point) => p.x >= 0.02 && p.x <= 0.98 && p.y >= 0.04 && p.y <= 0.99;
// The day maps are drawn with Google Maps in the browser, with the same key as the trip's Map screen.
const googleMaps = computed(() => (usePage().props as { googleMaps?: { browserKey?: string; mapId?: string } }).googleMaps ?? {});
const viewing = ref<MapPhoto | null>(null);

function go(to: number) { index.value = Math.max(0, Math.min(slides.value.length - 1, to)); }
function onKey(e: KeyboardEvent) {
    if (e.key === 'Escape' && viewing.value) { viewing.value = null; return; }
    if (e.key === 'Escape') emit('close');
    else if (['ArrowRight', 'PageDown', ' '].includes(e.key)) { e.preventDefault(); go(index.value + 1); }
    else if (['ArrowLeft', 'PageUp'].includes(e.key)) { e.preventDefault(); go(index.value - 1); }
}
let touchX = 0;
let multiTouch = false;
// One-finger swipes move between slides; two fingers pan and zoom the map.
const touchStart = (e: TouchEvent) => { multiTouch = e.touches.length > 1; touchX = e.touches[0]?.clientX ?? 0; };
const touchEnd = (e: TouchEvent) => { if (multiTouch || e.touches.length) { multiTouch = e.touches.length > 0; return; } const dx = (e.changedTouches[0]?.clientX ?? 0) - touchX; if (Math.abs(dx) > 50) go(index.value + (dx < 0 ? 1 : -1)); };
function fullscreen() { if (document.fullscreenElement) void document.exitFullscreen(); else void root.value?.requestFullscreen?.().catch(() => {}); }
function print() { window.print(); }

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
onBeforeUnmount(() => { window.removeEventListener('keydown', onKey); document.body.classList.remove('presenting'); if (document.fullscreenElement) void document.exitFullscreen(); });
</script>

<template>
    <Teleport to="body">
    <div ref="root" class="deck" role="dialog" aria-modal="true" aria-label="Trip presentation" tabindex="-1" @touchstart.passive="touchStart" @touchend="touchEnd">
        <header class="deck-bar">
            <strong class="deck-title">{{ deck?.title ?? 'Trip presentation' }}</strong>
            <span v-if="slides.length" class="deck-count">{{ index + 1 }} / {{ slides.length }}</span>
            <button type="button" class="deck-icon" aria-label="Print or save as PDF" title="Print or save as PDF" :disabled="!deck" @click="print"><Printer :size="18" /></button>
            <button type="button" class="deck-icon hide-phone" aria-label="Full screen" title="Full screen" @click="fullscreen"><Maximize2 :size="18" /></button>
            <button type="button" class="deck-icon" aria-label="Close presentation" @click="emit('close')"><X :size="20" /></button>
        </header>

        <div v-if="error" class="deck-state"><p>{{ error }}</p><button type="button" class="btn secondary" @click="emit('close')">Close</button></div>
        <div v-else-if="!deck" class="deck-state"><LoaderCircle class="spin" :size="28" /><p>Putting your trip together: maps, routes and photos…</p></div>

        <main v-else class="deck-stage">
            <section v-for="(slide, i) in slides" :key="i" class="slide" :class="['slide-' + slide.kind, { current: i === index }]" :aria-hidden="i !== index">
                <!-- Cover -->
                <template v-if="slide.kind === 'cover'">
                    <img v-if="deck.cover_url" :src="deck.cover_url" alt="" class="cover-photo" />
                    <div class="cover-text">
                        <span class="eyebrow">{{ deck.ended ? 'Trip recap' : 'Trip presentation' }}</span>
                        <h1>{{ deck.title }}</h1>
                        <p class="cover-meta">{{ deck.destination }} · {{ deck.dates }}</p>
                        <p class="cover-stats"><span v-for="s in deck.stats" :key="s.label"><b>{{ s.value }}</b> {{ s.label }}</span></p>
                    </div>
                </template>

                <!-- Overview -->
                <template v-else-if="slide.kind === 'overview'">
                    <h2>The trip at a glance</h2>
                    <ol class="overview">
                        <li v-for="day in deck.days" :key="day.date" @click="go(slides.findIndex(s => s.day === day))">
                            <span class="day-chip">Day {{ day.day_number }}</span>
                            <span class="overview-route"><strong>{{ day.route }}</strong><small>{{ day.label.split(' · ')[1] }} · {{ day.stops.length }} {{ day.stops.length === 1 ? 'stop' : 'stops' }} · {{ day.photo_count }} {{ day.photo_count === 1 ? 'photo' : 'photos' }}</small></span>
                            <span v-if="day.spent_minor" class="overview-spent">{{ money(day.spent_minor) }}</span>
                        </li>
                    </ol>
                    <p v-if="!deck.days.length" class="muted">Add itinerary stops or post photos and they will show up here.</p>
                    <p v-if="deck.spent_minor" class="overview-total">Group spending so far: <strong>{{ money(deck.spent_minor) }}</strong></p>
                </template>

                <!-- A day's map and itinerary -->
                <template v-else-if="slide.kind === 'map' && slide.day">
                    <div class="slide-head"><span class="eyebrow">{{ slide.day.label }}</span><h2>{{ slide.day.route }}</h2></div>
                    <div class="map-layout">
                        <PresentationMap v-if="googleMaps.browserKey && i === index && (slide.day.stops.length || slide.day.pins?.length)" :stops="slide.day.stops" :pins="slide.day.pins ?? []" :browser-key="googleMaps.browserKey" :map-id="googleMaps.mapId ?? ''" @open="viewing = $event" />
                        <div v-else-if="slide.day.map" class="map-frame">
                            <img :src="slide.day.map.url" :alt="`Map of the route on ${slide.day.label}`" loading="lazy" />
                            <figure v-for="(pin, p) in slide.day.map.pins.filter(onMap)" :key="p" class="pin" :style="{ left: pin.x * 100 + '%', top: pin.y * 100 + '%' }">
                                <img :src="pin.url" :alt="pin.caption ?? 'Photo'" loading="lazy" />
                            </figure>
                        </div>
                        <div v-else class="map-frame map-empty"><p>No map for this day: the stops have no places yet.</p></div>
                        <ol class="stops">
                            <li v-for="stop in slide.day.stops" :key="stop.n" :class="{ done: stop.done }">
                                <span class="stop-n" :class="{ off: !stop.on_map }">{{ stop.n }}</span>
                                <span><strong>{{ stop.title }}</strong><small>{{ stop.time }}<template v-if="stop.location"> · {{ stop.location }}</template></small></span>
                            </li>
                            <li v-if="!slide.day.stops.length" class="muted">No itinerary stops; photos from the day are pinned on the map.</li>
                        </ol>
                    </div>
                </template>

                <!-- A day's photos -->
                <template v-else-if="slide.kind === 'photos' && slide.day">
                    <div class="slide-head"><span class="eyebrow">{{ slide.day.label }}</span><h2>Moments from the day</h2></div>
                    <div class="photo-grid" :class="'count-' + slide.day.photos.length">
                        <figure v-for="(photo, p) in slide.day.photos" :key="p">
                            <img :src="photo.url" :alt="photo.caption ?? 'Trip photo'" loading="lazy" />
                            <figcaption><span v-if="photo.caption">{{ photo.caption }}</span><small>{{ [photo.by, photo.time].filter(Boolean).join(' · ') }}</small></figcaption>
                        </figure>
                    </div>
                </template>

                <!-- Budget -->
                <template v-else-if="slide.kind === 'budget'">
                    <div class="slide-head"><span class="eyebrow"><Sparkles :size="14" aria-hidden="true" /> Planned by AI · actual from your expenses</span><h2>Budget: planned vs spent</h2></div>
                    <div v-if="!budget && !budgetError" class="deck-state small"><LoaderCircle class="spin" :size="22" /><p>The AI is estimating your budget from the itinerary…</p></div>
                    <div v-else-if="budgetError" class="deck-state small"><p>{{ budgetError }}</p><button type="button" class="btn secondary" @click="loadBudget">Try again</button></div>
                    <template v-else-if="budget">
                        <div class="budget-totals">
                            <div><span>Planned (AI estimate)</span><strong>{{ budget.available && !budget.failed ? money(budget.total_estimate_minor) : '—' }}</strong><small v-if="budget.available && !budget.failed">{{ money(budget.per_person_estimate_minor) }} per person</small></div>
                            <div><span>Actually spent</span><strong>{{ money(budget.total_actual_minor) }}</strong><small>{{ money(budget.per_person_actual_minor) }} per person · {{ budget.travellers }} travellers</small></div>
                            <div v-if="budget.available && !budget.failed && budget.total_estimate_minor"><span>{{ budget.total_actual_minor > budget.total_estimate_minor ? 'Over plan' : 'Under plan' }}</span><strong :class="budget.total_actual_minor > budget.total_estimate_minor ? 'over' : 'under'">{{ money(Math.abs(budget.total_actual_minor - budget.total_estimate_minor)) }}</strong></div>
                        </div>
                        <p v-if="!budget.available" class="muted">The AI isn't set up on this server, so only actual spending is shown.</p>
                        <p v-else-if="budget.failed" class="muted">The AI couldn't estimate the budget right now. <button type="button" class="link" @click="loadBudget">Try again</button></p>
                        <div class="budget-layout">
                            <div class="budget-days">
                                <p class="legend"><i class="planned" /> Planned <i class="actual" /> Spent</p>
                                <div v-for="d in budget.days" :key="d.date" class="budget-day" :title="d.items.map(i => `${i.label}: ${money(i.amount_minor)}`).join('\n')">
                                    <span class="budget-label">{{ d.label }}</span>
                                    <span class="bars">
                                        <span class="bar planned" :style="{ width: (d.estimate_minor / budgetMax) * 100 + '%' }" />
                                        <span class="bar actual" :style="{ width: (d.actual_minor / budgetMax) * 100 + '%' }" />
                                    </span>
                                    <span class="budget-values">{{ money(d.estimate_minor) }} / <b>{{ money(d.actual_minor) }}</b></span>
                                </div>
                            </div>
                            <div class="budget-side">
                                <template v-if="budget.categories.length">
                                    <h3>Planned by category</h3>
                                    <div v-for="c in budget.categories" :key="c.category" class="category"><span>{{ categoryLabel[c.category] ?? c.category }}</span><span class="cat-bar"><i :style="{ width: (c.estimate_minor / categoryMax) * 100 + '%' }" /></span><b>{{ money(c.estimate_minor) }}</b></div>
                                </template>
                                <template v-if="budget.actual_categories.length">
                                    <h3>Spent by category</h3>
                                    <div v-for="c in budget.actual_categories.slice(0, 5)" :key="c.name" class="category plain"><span>{{ c.name }}</span><b>{{ money(c.amount_minor) }}</b></div>
                                </template>
                                <template v-if="budget.notes.length">
                                    <h3>AI notes</h3>
                                    <ul class="notes"><li v-for="n in budget.notes" :key="n">{{ n }}</li></ul>
                                </template>
                            </div>
                        </div>
                    </template>
                </template>

                <!-- Closing -->
                <template v-else-if="slide.kind === 'end'">
                    <img v-if="deck.cover_url" :src="deck.cover_url" alt="" class="cover-photo dim" />
                    <div class="cover-text center">
                        <h1>Salamat, barkada!</h1>
                        <p class="cover-meta">{{ deck.title }} · {{ deck.dates }}</p>
                        <p class="cover-stats"><span v-for="s in deck.stats" :key="s.label"><b>{{ s.value }}</b> {{ s.label }}</span></p>
                    </div>
                </template>
            </section>
        </main>

        <footer v-if="deck" class="deck-nav">
            <button type="button" class="deck-arrow" aria-label="Previous slide" :disabled="index === 0" @click="go(index - 1)"><ChevronLeft :size="22" /></button>
            <div class="dots" role="tablist" aria-label="Slides">
                <button v-for="(s, i) in slides" :key="i" type="button" role="tab" :aria-selected="i === index" :aria-label="`Slide ${i + 1}`" :class="{ on: i === index }" @click="go(i)" />
            </div>
            <button type="button" class="deck-arrow" aria-label="Next slide" :disabled="index === slides.length - 1" @click="go(index + 1)"><ChevronRight :size="22" /></button>
        </footer>
        <div v-if="viewing" class="viewer" role="dialog" aria-label="Photo" @click="viewing = null">
            <figure @click.stop>
                <img :src="viewing.url" :alt="viewing.caption ?? 'Photo'" />
                <figcaption><span v-if="viewing.caption">{{ viewing.caption }}</span><small>{{ [viewing.by, viewing.time].filter(Boolean).join(' · ') }}</small></figcaption>
            </figure>
            <button type="button" class="deck-icon viewer-close" aria-label="Close photo" @click="viewing = null"><X :size="20" /></button>
        </div>
    </div>
    </Teleport>
</template>

<style scoped>
.deck{position:fixed;inset:0;z-index:80;display:grid;grid-template-rows:auto minmax(0,1fr) auto;background:#10231b;color:#f4f1e8;outline:none}
.deck-bar{display:flex;align-items:center;gap:8px;padding:10px 16px}
.deck-title{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:15px}
.deck-count{font-size:13px;color:#b7c8b9;font-variant-numeric:tabular-nums}
.deck-icon,.deck-arrow{display:grid;place-items:center;width:40px;height:40px;border-radius:50%;border:0;background:rgba(255,255,255,.1);color:inherit;cursor:pointer}
.deck-icon:disabled,.deck-arrow:disabled{opacity:.35;cursor:default}
.deck-state{display:grid;place-items:center;align-content:center;gap:12px;padding:24px;text-align:center;color:#d5dfd3}
.deck-state.small{min-height:200px}
.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.deck-stage{position:relative;min-height:0;padding:0 16px}
.slide{display:none;position:relative;height:100%;max-width:1200px;margin:0 auto;border-radius:20px;overflow:auto;background:#f8f6ef;color:#1d2d24;padding:28px 32px}
.slide.current{display:block;animation:fade .35s ease}
@keyframes fade{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.slide h1{font-size:clamp(32px,6vw,64px);line-height:1.05;margin:6px 0 10px}
.slide h2{font-size:clamp(22px,3.2vw,34px);line-height:1.15;margin:4px 0 0}
.slide h3{font-size:14px;margin:16px 0 8px;color:#3f5a4b}
.eyebrow{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#c2603a}
.muted{color:#6c7c72}
.slide-head{margin-bottom:18px}
.slide-cover,.slide-end{padding:0;background:#214e3e;color:#fff}
.cover-photo{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.cover-photo.dim{filter:brightness(.55)}
.cover-text{position:absolute;inset:auto 0 0 0;padding:40px 36px 36px;background:linear-gradient(transparent,rgba(9,26,19,.88))}
.cover-text.center{inset:0;display:grid;align-content:center;justify-items:center;text-align:center;background:rgba(9,26,19,.35)}
.slide-cover .eyebrow,.slide-end .eyebrow{color:#ffc9a8}
.cover-meta{font-size:clamp(15px,2vw,20px);opacity:.92;margin:0}
.cover-stats{display:flex;flex-wrap:wrap;gap:8px 18px;margin:16px 0 0;font-size:14px}
.cover-stats b{font-size:20px}
.overview{list-style:none;margin:18px 0 0;padding:0;display:grid;gap:8px}
.overview li{display:flex;align-items:center;gap:14px;padding:12px 14px;border-radius:14px;background:#fff;border:1px solid #e3e9e1;cursor:pointer}
.day-chip{flex:none;padding:6px 10px;border-radius:999px;background:#214e3e;color:#fff;font-size:12px;font-weight:700}
.overview-route{flex:1;min-width:0;display:grid}
.overview-route small{color:#6c7c72}
.overview-spent{font-weight:700;color:#3d7650}
.overview-total{margin-top:16px}
.map-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(220px,1fr);gap:20px;align-items:start}
.map-frame{position:relative;border-radius:16px;overflow:hidden;background:#e7ece2;aspect-ratio:640/400}
.map-frame>img{display:block;width:100%;height:100%;object-fit:cover}
.map-empty{display:grid;place-items:center;padding:20px;text-align:center;color:#6c7c72}
.viewer{position:absolute;inset:0;z-index:5;display:grid;place-items:center;padding:24px;background:rgba(6,16,12,.88)}
.viewer figure{margin:0;max-width:min(900px,100%);display:grid;gap:8px}
.viewer img{max-width:100%;max-height:75vh;border-radius:14px;object-fit:contain}
.viewer figcaption{display:grid;color:#fff;font-size:14px}.viewer figcaption small{opacity:.8}
.viewer-close{position:absolute;top:14px;right:14px}
.pin{position:absolute;margin:0;width:54px;height:54px;transform:translate(-50%,calc(-100% - 8px));border-radius:50%;border:3px solid #fff;box-shadow:0 4px 12px rgba(0,0,0,.35);background:#fff}
.pin::after{content:'';position:absolute;left:50%;bottom:-11px;transform:translateX(-50%);border:7px solid transparent;border-top:8px solid #fff}
.pin img{width:100%;height:100%;border-radius:50%;object-fit:cover;display:block}
.stops{list-style:none;margin:0;padding:0;display:grid;gap:10px}
.stops li{display:flex;gap:10px;align-items:flex-start}
.stops li>span:last-child{display:grid}
.stops small{color:#6c7c72}
.stop-n{flex:none;display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#d9643a;color:#fff;font-weight:700;font-size:13px}
.stop-n.off{background:#b8c2b9}
.stops li.done strong::after{content:' ✓';color:#3d7650}
.photo-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}
.photo-grid.count-1{grid-template-columns:1fr}.photo-grid.count-2,.photo-grid.count-4{grid-template-columns:repeat(2,minmax(0,1fr))}
.photo-grid figure{margin:0;position:relative;border-radius:14px;overflow:hidden;background:#e7ece2;aspect-ratio:4/3}
.photo-grid.count-1 figure{aspect-ratio:16/9}
.photo-grid img{width:100%;height:100%;object-fit:cover;display:block}
.photo-grid figcaption{position:absolute;inset:auto 0 0 0;padding:20px 10px 8px;background:linear-gradient(transparent,rgba(0,0,0,.65));color:#fff;font-size:13px;display:grid}
.photo-grid figcaption small{opacity:.85}
.budget-totals{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-bottom:16px}
.budget-totals>div{display:grid;gap:2px;padding:14px 16px;border-radius:14px;background:#fff;border:1px solid #e3e9e1}
.budget-totals span{font-size:13px;color:#6c7c72}.budget-totals strong{font-size:24px}.budget-totals small{color:#6c7c72}
.over{color:#a6573e}.under{color:#3d7650}
.budget-layout{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(220px,1fr);gap:24px}
.legend{display:flex;align-items:center;gap:6px;font-size:12px;color:#6c7c72;margin:0 0 8px}
.legend i{display:inline-block;width:12px;height:12px;border-radius:3px}
.legend .actual{margin-left:10px}
.planned{background:#cfdcc4}.actual{background:#214e3e}
.budget-day{display:grid;grid-template-columns:110px minmax(0,1fr) auto;gap:10px;align-items:center;padding:6px 0;font-size:13px}
.bars{display:grid;gap:3px}
.bar{display:block;height:9px;border-radius:5px;min-width:2px}
.budget-values{font-variant-numeric:tabular-nums;color:#6c7c72;white-space:nowrap}.budget-values b{color:#1d2d24}
.category{display:grid;grid-template-columns:minmax(0,1fr) 80px auto;gap:10px;align-items:center;font-size:13px;padding:4px 0}
.category.plain{grid-template-columns:minmax(0,1fr) auto}
.cat-bar{height:8px;border-radius:4px;background:#eef2ea;overflow:hidden}.cat-bar i{display:block;height:100%;background:#d9643a}
.notes{margin:0;padding-left:18px;font-size:13px;line-height:1.6}
.link{border:0;background:none;color:#214e3e;text-decoration:underline;cursor:pointer;padding:0;font:inherit}
.deck-nav{display:flex;align-items:center;justify-content:center;gap:12px;padding:10px 16px calc(10px + env(safe-area-inset-bottom))}
.dots{display:flex;flex-wrap:wrap;justify-content:center;gap:6px;max-width:60vw}
.dots button{width:8px;height:8px;padding:0;border-radius:50%;border:0;background:rgba(255,255,255,.3);cursor:pointer}
.dots button.on{background:#fff;width:20px;border-radius:4px}
@media (max-width:760px){
    .slide{padding:20px 18px;border-radius:16px}
    .map-layout,.budget-layout{grid-template-columns:1fr}
    .photo-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
    .pin{width:40px;height:40px}
    .budget-day{grid-template-columns:76px minmax(0,1fr)}
    .budget-values{grid-column:2}
    .hide-phone{display:none}
    .cover-text{padding:28px 20px 24px}
}
@media print{
    .deck{position:static;display:block;background:#fff;color:#000}
    .deck-bar,.deck-nav{display:none}
    .deck-stage{padding:0}
    .slide{display:block!important;height:auto;min-height:100vh;overflow:visible;border-radius:0;page-break-after:always;break-after:page;animation:none}
    .slide-cover,.slide-end{min-height:100vh}
}
</style>

<style>
@media print{body.presenting>*:not(.deck){display:none!important}body.presenting{background:#fff}}
</style>
