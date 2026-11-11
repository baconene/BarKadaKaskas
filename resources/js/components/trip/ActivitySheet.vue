<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { CircleCheck, Circle, Clock, Map as MapIcon, MapPin, Navigation, Pencil, Route, Wallet, X } from 'lucide-vue-next';
import type { LegResult } from '@/components/ItineraryRouteMap.vue';
import { costRange, dayKey, transport, type ItineraryStop } from '@/lib/itinerary';
import { appFetch } from '@/lib/uploads';
import { googleMapsUrl } from '@/lib/maps';

const props = defineProps<{ tripId: number; item: ItineraryStop | null; previous: ItineraryStop | null; leg: LegResult | null; timezone: string; isOrganizer: boolean; done: boolean; doneError?: string }>();
const emit = defineEmits<{ close: []; edit: [item: ItineraryStop]; toggleDone: [id: number]; showOnMap: [id: number] }>();
const dialog = ref<HTMLDialogElement>();

watch(() => props.item, async item => {
    await nextTick();
    if (item && !dialog.value?.open) dialog.value?.showModal();
    if (!item && dialog.value?.open) dialog.value.close();
});
// Photos of the place from the web (Google Places, then Wikimedia Commons), loaded when the details open.
type PlacePhoto = { url: string; credit: string | null; source: string };
const photos = ref<PlacePhoto[]>([]);
const photosLoading = ref(false);
const viewing = ref<number | null>(null);
const photoCache = new Map<number, PlacePhoto[]>();
watch(() => props.item?.id, async id => {
    viewing.value = null;
    if (!id) return;
    if (photoCache.has(id)) { photos.value = photoCache.get(id)!; return; }
    photos.value = []; photosLoading.value = true;
    try { const { data } = await appFetch<{ data: PlacePhoto[] }>(`/trips/${props.tripId}/itinerary/${id}/photos`); photoCache.set(id, data); if (props.item?.id === id) photos.value = data; }
    catch { /* photos are a nice extra: leave the strip empty */ }
    finally { photosLoading.value = false; }
}, { immediate: true });
// Tapping the dimmed backdrop closes the sheet.
function backdropClick(event: MouseEvent) { if (event.target === dialog.value) emit('close'); }

const fmt = (value: string, options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-PH', { ...options, timeZone: props.timezone }).format(new Date(value));
const when = computed(() => {
    if (!props.item) return '';
    const { starts_at: start, ends_at: end } = props.item;
    const day = fmt(start, { weekday: 'short', month: 'short', day: 'numeric' });
    if (start === end) return `${day} · ${fmt(start, { hour: 'numeric', minute: '2-digit' })}`;
    const nextDay = dayKey(end, props.timezone) !== dayKey(start, props.timezone);
    return `${day} · ${fmt(start, { hour: 'numeric', minute: '2-digit' })} – ${fmt(end, { hour: 'numeric', minute: '2-digit' })}${nextDay ? ' (next day)' : ''}`;
});
const mode = computed(() => transport(props.item?.transport_mode ?? null));
const navigateUrl = computed(() => {
    const item = props.item;
    if (!item?.location) return null;
    const destination = item.latitude != null && item.longitude != null ? `${item.latitude},${item.longitude}` : item.location;
    return `https://www.google.com/maps/dir/?${new URLSearchParams({ api: '1', destination })}`;
});
</script>

<template>
    <dialog ref="dialog" class="activity-sheet" :aria-labelledby="item ? `sheet-title-${item.id}` : undefined" @cancel.prevent="emit('close')" @click="backdropClick">
        <div v-if="item" class="sheet-body">
            <span class="grabber" aria-hidden="true" />
            <div class="sheet-head">
                <div>
                    <p class="sheet-when"><Clock :size="13" aria-hidden="true" /> {{ when }}</p>
                    <h2 :id="`sheet-title-${item.id}`">{{ item.title }}</h2>
                </div>
                <button type="button" class="sheet-close" aria-label="Close details" @click="emit('close')"><X :size="18" /></button>
            </div>

            <div v-if="photosLoading || photos.length" class="place-photos" aria-label="Photos of this place">
                <template v-if="photosLoading"><span v-for="n in 4" :key="n" class="photo-skeleton" aria-hidden="true" /></template>
                <button v-for="(p, i) in photos" v-else :key="p.url" type="button" class="place-photo" :aria-label="`Photo ${i + 1} of ${photos.length}${p.credit ? `, by ${p.credit}` : ''}`" @click="viewing = i">
                    <img :src="p.url" alt="" loading="lazy" referrerpolicy="no-referrer" />
                </button>
            </div>
            <p v-if="photos.length" class="photo-credit">Photos from {{ [...new Set(photos.map(p => p.source))].join(' and ') }}</p>
            <div v-if="viewing !== null && photos[viewing]" class="photo-viewer" role="dialog" aria-label="Photo" @click.self="viewing = null">
                <img :src="photos[viewing].url" alt="" referrerpolicy="no-referrer" />
                <p>{{ viewing + 1 }} / {{ photos.length }}<template v-if="photos[viewing].credit"> · {{ photos[viewing].credit }}</template></p>
                <div class="viewer-nav">
                    <button type="button" class="btn secondary" :disabled="viewing === 0" @click="viewing--">Previous</button>
                    <button type="button" class="btn secondary" @click="viewing = null">Close</button>
                    <button type="button" class="btn secondary" :disabled="viewing === photos.length - 1" @click="viewing++">Next</button>
                </div>
            </div>

            <a v-if="item.location" class="sheet-place" :href="googleMapsUrl(item.location)" target="_blank" rel="noopener noreferrer"><MapPin :size="15" aria-hidden="true" /> <span>{{ item.location }}</span><span class="sr-only"> (opens Google Maps)</span></a>

            <div class="sheet-actions">
                <a v-if="navigateUrl" class="btn" :href="navigateUrl" target="_blank" rel="noopener noreferrer"><Navigation :size="15" aria-hidden="true" /> Navigate</a>
                <button v-if="previous" type="button" class="btn secondary" @click="emit('showOnMap', item.id)"><MapIcon :size="15" aria-hidden="true" /> Show on map</button>
                <button type="button" class="btn secondary" :aria-pressed="done" @click="emit('toggleDone', item.id)">
                    <CircleCheck v-if="done" :size="15" aria-hidden="true" /><Circle v-else :size="15" aria-hidden="true" /> {{ done ? 'Done' : 'Mark done' }}
                </button>
            </div>

            <dl class="sheet-facts">
                <div v-if="previous"><dt><Route :size="13" aria-hidden="true" /> Getting here</dt><dd>{{ mode?.label ?? 'Transport to be decided' }} from {{ previous.title }}<template v-if="leg"> · {{ leg.summary }}</template></dd></div>
                <div v-if="costRange(item.cost_min_minor, item.cost_max_minor)"><dt><Wallet :size="13" aria-hidden="true" /> Estimated cost</dt><dd><strong>{{ costRange(item.cost_min_minor, item.cost_max_minor) }}</strong> per person<span v-if="item.cost_note" class="cost-note">{{ item.cost_note }}</span></dd></div>
            </dl>
            <div v-if="item.description" class="sheet-notes"><h3>Notes</h3><p>{{ item.description }}</p></div>
            <p v-if="doneError" class="sheet-done-error" role="alert">{{ doneError }}</p>
            <p v-else class="sheet-done-note">Everyone on the trip sees what’s done.</p>

            <button v-if="isOrganizer" type="button" class="btn secondary sheet-edit" @click="emit('edit', item)"><Pencil :size="15" aria-hidden="true" /> Edit activity</button>
        </div>
    </dialog>
</template>

<style scoped>
.activity-sheet{border:0;padding:0;margin:auto auto 0;width:100%;max-width:560px;max-height:min(86dvh,760px);border-radius:22px 22px 0 0;background:#fffefb;color:#172f2a;box-shadow:0 -10px 40px #1d33281f}
.activity-sheet::backdrop{background:#10221a66}
.activity-sheet[open]{animation:sheet-up .22s ease-out}
.sheet-body{display:grid;gap:14px;padding:10px 20px calc(20px + env(safe-area-inset-bottom));overflow-y:auto;max-height:inherit}
.grabber{justify-self:center;width:40px;height:4px;border-radius:999px;background:#d3dccd}
.sheet-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
.sheet-when{display:flex;align-items:center;gap:6px;font-size:12px;font-weight:700;color:#5d7051}
.sheet-head h2{font-size:21px;font-weight:750;letter-spacing:-.5px;line-height:1.25;margin-top:4px;overflow-wrap:anywhere}
.sheet-close{flex-shrink:0;display:grid;place-items:center;width:44px;height:44px;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.place-photos{display:flex;gap:8px;overflow-x:auto;scroll-snap-type:x mandatory;margin:0 -20px;padding:0 20px 4px}
.place-photo{flex:none;width:200px;height:140px;padding:0;border:0;border-radius:14px;overflow:hidden;background:#e8efe3;scroll-snap-align:start;cursor:zoom-in}
.place-photo img{width:100%;height:100%;object-fit:cover;display:block}
.photo-skeleton{flex:none;width:200px;height:140px;border-radius:14px;background:linear-gradient(90deg,#edf2e9,#f7faf4,#edf2e9);background-size:200% 100%;animation:shimmer 1.2s infinite}
.photo-credit{font-size:11px;color:#8a978f;margin-top:-8px}
.photo-viewer{position:fixed;inset:0;z-index:5;background:#0d1512f2;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:16px}
.photo-viewer img{max-width:100%;max-height:72dvh;border-radius:12px;object-fit:contain}
.photo-viewer p{color:#dfe8da;font-size:12px;text-align:center}
.viewer-nav{display:flex;gap:8px}
@keyframes shimmer{to{background-position:-200% 0}}
.sheet-place{display:flex;gap:7px;align-items:flex-start;font-size:13px;color:#4f6156;line-height:1.5}
.sheet-place:hover span:first-of-type{text-decoration:underline;text-underline-offset:3px}
.sheet-place svg{flex-shrink:0;margin-top:2px;color:#72883a}
.sheet-actions{display:flex;flex-wrap:wrap;gap:8px}
.sheet-actions .btn{min-height:44px;flex:1 1 auto}
.sheet-facts{display:grid;gap:12px;background:#f5f8f1;border:1px solid #e3eadc;border-radius:14px;padding:14px}
.sheet-facts dt{display:flex;align-items:center;gap:6px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#76846e}
.sheet-facts dd{margin-top:4px;font-size:13px;line-height:1.55}
.cost-note{display:block;font-size:12px;color:#5f6f66;margin-top:3px}
.sheet-notes h3{font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#76846e}
.sheet-notes p{margin-top:6px;white-space:pre-wrap;font-size:14px;line-height:1.7;color:#33463d}
.sheet-done-note{font-size:11px;color:#8a978f}
.sheet-done-error{font-size:12px;color:#b42318}
.sheet-edit{min-height:44px}
@keyframes sheet-up{from{transform:translateY(30px);opacity:.4}to{transform:none;opacity:1}}
@media(min-width:768px){.activity-sheet{margin:auto;border-radius:22px}.grabber{display:none}.sheet-body{padding-top:20px}}
@media(prefers-reduced-motion:reduce){.activity-sheet[open]{animation:none}}
</style>
