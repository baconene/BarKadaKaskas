<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { router } from '@inertiajs/vue3';
import { LoaderCircle, Sparkles, X } from 'lucide-vue-next';
import { costRange, dayLabel, transport } from '@/lib/itinerary';
import { appFetch } from '@/lib/uploads';

/**
 * AI trip planner: describe the trip, review the suggested activities, untick any you don't want, and add the
 * rest to the itinerary in one go. Nothing is saved until "Add" is pressed.
 */
type Suggestion = { title: string; location: string | null; description: string | null; starts_at: string; ends_at: string; transport_mode: string | null; cost_min_minor: number | null; cost_max_minor: number | null; cost_note: string | null };
const props = defineProps<{ open: boolean; tripId: number; destination: string; initialPrompt?: string | null; autoGenerate?: boolean }>();
const emit = defineEmits<{ close: [] }>();

const dialog = ref<HTMLDialogElement>();
const prompt = ref('');
const suggestions = ref<(Suggestion & { keep: boolean })[]>([]);
const generating = ref(false);
const saving = ref(false);
const error = ref('');

watch(() => props.open, async open => {
    await nextTick();
    if (open) {
        if (!dialog.value?.open) dialog.value?.showModal();
        if (props.initialPrompt && !prompt.value) prompt.value = props.initialPrompt;
        if (props.autoGenerate && prompt.value.trim() && !suggestions.value.length) generate();
    } else if (dialog.value?.open) dialog.value.close();
}, { immediate: true });

async function generate() {
    if (generating.value || prompt.value.trim().length < 3) return;
    generating.value = true; error.value = '';
    const body = new FormData(); body.append('prompt', prompt.value.trim());
    try {
        const { data } = await appFetch<{ data: Suggestion[] }>(`/trips/${props.tripId}/itinerary/suggestions`, { method: 'POST', body });
        suggestions.value = data.map(s => ({ ...s, keep: true }));
    } catch (e) { error.value = e instanceof Error ? e.message : 'The AI planner could not answer. Try again.'; }
    finally { generating.value = false; }
}

const kept = computed(() => suggestions.value.filter(s => s.keep));
const days = computed(() => {
    const groups = new Map<string, (typeof suggestions.value)[number][]>();
    suggestions.value.forEach(s => { const key = s.starts_at.slice(0, 10); groups.set(key, [...(groups.get(key) ?? []), s]); });
    return [...groups].map(([key, items]) => ({ key, label: dayLabel(key), items }));
});
const clock = (local: string) => { const [h, m] = local.slice(11, 16).split(':').map(Number); return `${((h + 11) % 12) + 1}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };
function setDay(key: string, keep: boolean) { suggestions.value.forEach(s => { if (s.starts_at.startsWith(key)) s.keep = keep; }); }

async function add() {
    if (!kept.value.length || saving.value) return;
    saving.value = true; error.value = '';
    const body = new FormData();
    kept.value.forEach((s, i) => {
        (Object.keys(s) as (keyof Suggestion)[]).forEach(field => { const value = s[field]; if (value !== null && value !== undefined) body.append(`items[${i}][${field}]`, String(value)); });
    });
    try {
        await appFetch(`/trips/${props.tripId}/itinerary/batch`, { method: 'POST', body });
        suggestions.value = []; prompt.value = '';
        router.reload({ only: ['trip'] });
        emit('close');
    } catch (e) { error.value = e instanceof Error ? e.message : 'Could not add the activities. Try again.'; }
    finally { saving.value = false; }
}
</script>

<template>
    <dialog ref="dialog" class="planner" aria-labelledby="planner-title" @cancel.prevent="emit('close')" @click="$event.target === dialog && !generating && !saving && emit('close')">
        <div v-if="open" class="sheet">
            <header>
                <div><p class="eyebrow"><Sparkles :size="13" aria-hidden="true" /> AI trip planner</p><h2 id="planner-title">Plan {{ destination }} with AI</h2></div>
                <button type="button" class="close" aria-label="Close" :disabled="saving" @click="emit('close')"><X :size="18" /></button>
            </header>

            <form class="ask" @submit.prevent="generate">
                <label class="field">What kind of trip is it?
                    <textarea v-model="prompt" rows="3" maxlength="2000" required placeholder="e.g. Beaches and waterfalls, budget-friendly, local food, we arrive by ferry on day 1 around 3 PM" />
                </label>
                <button class="btn" :disabled="generating || prompt.trim().length < 3"><LoaderCircle v-if="generating" :size="16" class="spin" aria-hidden="true" /><Sparkles v-else :size="16" aria-hidden="true" /> {{ generating ? 'Planning… this can take a minute' : suggestions.length ? 'Suggest again' : 'Suggest an itinerary' }}</button>
            </form>
            <p v-if="error" class="error" role="alert">{{ error }}</p>

            <template v-if="suggestions.length">
                <p class="muted">Untick anything you don’t want. You can edit each activity after it’s added.</p>
                <section v-for="day in days" :key="day.key" class="day">
                    <h3>{{ day.label }} <span class="day-tools"><button type="button" class="link" @click="setDay(day.key, true)">All</button> · <button type="button" class="link" @click="setDay(day.key, false)">None</button></span></h3>
                    <label v-for="(s, i) in day.items" :key="`${day.key}-${i}`" class="item" :class="{ off: !s.keep }">
                        <input v-model="s.keep" type="checkbox" />
                        <span class="time">{{ clock(s.starts_at) }}</span>
                        <span class="body">
                            <strong>{{ s.title }}</strong>
                            <small v-if="s.location">{{ s.location }}</small>
                            <small class="meta"><template v-if="transport(s.transport_mode)">{{ transport(s.transport_mode)?.label }}</template><template v-if="transport(s.transport_mode) && costRange(s.cost_min_minor, s.cost_max_minor)"> · </template><template v-if="costRange(s.cost_min_minor, s.cost_max_minor)">{{ costRange(s.cost_min_minor, s.cost_max_minor) }}/person</template></small>
                            <small v-if="s.description" class="notes">{{ s.description }}</small>
                        </span>
                    </label>
                </section>
                <div class="actions">
                    <button type="button" class="btn" :disabled="saving || !kept.length" @click="add">{{ saving ? 'Adding…' : `Add ${kept.length} ${kept.length === 1 ? 'activity' : 'activities'} to the itinerary` }}</button>
                    <button type="button" class="btn secondary" :disabled="saving" @click="emit('close')">Cancel</button>
                </div>
            </template>
        </div>
    </dialog>
</template>

<style scoped>
.planner{border:0;padding:0;margin:auto auto 0;width:min(640px,100%);max-height:92vh;border-radius:22px 22px 0 0;background:#fff;color:#1d2b24}
.planner::backdrop{background:rgba(20,32,26,.45)}
@media(min-width:640px){.planner{margin:auto;border-radius:22px}}
.sheet{padding:20px 18px 24px;display:grid;gap:14px;overflow:auto;max-height:92vh}
header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
.eyebrow{display:flex;align-items:center;gap:5px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#76846e}
h2{font-size:20px;font-weight:800;letter-spacing:-.4px}
.close{display:inline-flex;align-items:center;justify-content:center;width:36px;height:36px;border-radius:999px;background:#f2f5ed;border:1px solid #e2e8da}
.ask{display:grid;gap:10px}
.ask textarea{width:100%}
.error{color:#b42318;font-size:13px}
.muted{color:#6c7c72;font-size:13px}
.day{border:1px solid #e4ebdf;border-radius:14px;padding:10px 12px}
.day h3{display:flex;justify-content:space-between;align-items:center;font-size:14px;font-weight:750;margin-bottom:4px}
.day-tools{font-size:12px;font-weight:600;color:#6c7c72}
.link{border:0;background:none;color:#3d7650;font-weight:700;cursor:pointer;padding:2px}
.item{display:grid;grid-template-columns:auto 64px minmax(0,1fr);gap:10px;align-items:start;padding:9px 0;border-top:1px solid #eef2ea;cursor:pointer}
.item input{margin-top:3px;width:18px;height:18px}
.item.off{opacity:.45}
.time{font-size:12px;font-weight:700;color:#3f5a2a;padding-top:2px}
.body{display:grid;gap:2px;min-width:0}
.body strong{font-size:14px}
.body small{font-size:12px;color:#6c7c72}
.notes{color:#4d5f55!important}
.actions{display:flex;gap:8px;flex-wrap:wrap;position:sticky;bottom:-24px;background:#fff;padding:10px 0 4px}
.actions .btn{flex:1;min-height:46px}
.spin{animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
</style>
