<script setup lang="ts">
import { useForm } from '@inertiajs/vue3';
import { ArrowLeft, ArrowRight, CalendarDays, Clock, Coins, Sparkles, X } from 'lucide-vue-next';
import { computed, nextTick, onMounted, ref } from 'vue';
import LocationSearch, { type PlaceDetails } from '@/components/LocationSearch.vue';
import { COMMON_ZONES, CURRENCIES, defaultsFor } from '@/lib/regions';

/**
 * "Plan a trip" as a two-step flow that fits a phone screen without scrolling:
 * 1. name, destination (with suggestions) and dates, where currency and time zone follow the destination;
 * 2. optional description and "plan it with AI", which can be skipped.
 */
defineProps<{ mapsKey?: string | null; aiPlanner?: boolean }>();
const emit = defineEmits<{ close: [] }>();

const dialog = ref<HTMLDialogElement | null>(null);
const nameInput = ref<HTMLInputElement | null>(null);
const step = ref<1 | 2>(1);
const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
const form = useForm({ name: '', destination: '', description: '', starts_on: '', ends_on: '', timezone: localZone || 'Asia/Manila', currency_code: 'PHP', ai_prompt: '' });
const pinned = ref<{ latitude: number; longitude: number } | null>(null);
const autoSet = ref(false); // currency / time zone were filled from the destination

const zones = computed(() => (COMMON_ZONES.includes(form.timezone) ? COMMON_ZONES : [form.timezone, ...COMMON_ZONES]));
const nights = computed(() => {
    if (!form.starts_on || !form.ends_on) return null;
    const days = Math.round((Date.parse(form.ends_on) - Date.parse(form.starts_on)) / 86_400_000);
    return days >= 0 ? days : null;
});
const stepOneReady = computed(() => form.name.trim() !== '' && form.destination.trim() !== '' && !!form.starts_on && !!form.ends_on && nights.value !== null);
const ideas = ['Beaches and waterfalls', 'Food trip', 'Budget-friendly', 'Chill, not too packed', 'Island hopping', 'Nightlife'];

function onPicked(place: PlaceDetails) {
    const { currency, timezone } = defaultsFor(place.countryCode, place.latitude, place.longitude);
    if (currency) form.currency_code = currency;
    if (timezone) form.timezone = timezone;
    autoSet.value = !!(currency || timezone);
}
function onStart() { if (form.ends_on && form.ends_on < form.starts_on) form.ends_on = form.starts_on; }
function addIdea(idea: string) { form.ai_prompt = form.ai_prompt.trim() ? `${form.ai_prompt.trim()}, ${idea.toLowerCase()}` : idea; }
async function next() { if (stepOneReady.value) { step.value = 2; await nextTick(); } }
function submit(skip = false) {
    form.transform(data => (skip ? { ...data, description: '', ai_prompt: '' } : data)).post('/trips', {
        onError: errors => { if (errors.name || errors.destination || errors.starts_on || errors.ends_on || errors.currency_code || errors.timezone) step.value = 1; },
    });
}

onMounted(async () => { dialog.value?.showModal(); await nextTick(); nameInput.value?.focus(); });
</script>

<template>
    <dialog ref="dialog" class="wizard" aria-labelledby="wizard-title" @close="emit('close')">
        <form class="body" @submit.prevent="step === 1 ? next() : submit()">
            <header>
                <button v-if="step === 2" type="button" class="icon-btn" aria-label="Back" @click="step = 1"><ArrowLeft :size="18" /></button>
                <div class="titles">
                    <p class="eyebrow">Step {{ step }} of 2</p>
                    <h2 id="wizard-title">{{ step === 1 ? 'Where are we going?' : 'Make it yours' }}</h2>
                </div>
                <button type="button" class="icon-btn" aria-label="Close" @click="dialog?.close()"><X :size="18" /></button>
            </header>
            <div class="progress" aria-hidden="true"><span :style="{ width: step === 1 ? '50%' : '100%' }" /></div>

            <section v-if="step === 1" class="fields">
                <label class="field">Trip name<input ref="nameInput" v-model="form.name" required maxlength="120" placeholder="The barkada goes to Siquijor" /></label>
                <div class="field">
                    <label for="trip-destination">Destination</label>
                    <LocationSearch id="trip-destination" v-model="form.destination" v-model:pinned="pinned" :browser-key="mapsKey ?? ''" :types="['(regions)']" compact placeholder="Search a city, island or country" :maxlength="200" @picked="onPicked" />
                </div>
                <div class="dates">
                    <label class="field">Starts<input v-model="form.starts_on" type="date" required @change="onStart" /></label>
                    <label class="field">Ends<input v-model="form.ends_on" type="date" :min="form.starts_on || undefined" required /></label>
                </div>
                <p class="length" :class="{ empty: nights === null }"><CalendarDays :size="14" aria-hidden="true" /> {{ nights === null ? 'Pick your dates' : `${nights + 1} day${nights === 0 ? '' : 's'} · ${nights} night${nights === 1 ? '' : 's'}` }}</p>
                <div class="locale">
                    <label class="mini"><Coins :size="14" aria-hidden="true" /><span class="sr-only">Currency</span><select v-model="form.currency_code" @change="autoSet = false"><option v-for="c in CURRENCIES" :key="c">{{ c }}</option></select></label>
                    <label class="mini zone"><Clock :size="14" aria-hidden="true" /><span class="sr-only">Time zone</span><select v-model="form.timezone" @change="autoSet = false"><option v-for="z in zones" :key="z" :value="z">{{ z.replace('_', ' ') }}</option></select></label>
                </div>
                <p class="hint muted">{{ autoSet ? 'Currency and time zone set from your destination. Change them if needed.' : 'Currency and time zone follow the destination you pick.' }}</p>
            </section>

            <section v-else class="fields">
                <label class="field">About this trip <span class="optional">optional</span><textarea v-model="form.description" rows="2" maxlength="4000" placeholder="e.g. Ate Joy's birthday trip, carpool from QC" /></label>
                <div v-if="aiPlanner" class="field ai">
                    <label for="trip-ai"><Sparkles :size="15" aria-hidden="true" /> Plan it with AI <span class="optional">optional</span></label>
                    <textarea id="trip-ai" v-model="form.ai_prompt" rows="3" maxlength="2000" placeholder="What do you want to do? AI suggests an itinerary for you to approve." />
                    <div class="ideas" role="group" aria-label="Ideas"><button v-for="idea in ideas" :key="idea" type="button" @click="addIdea(idea)">+ {{ idea }}</button></div>
                </div>
                <p v-else class="muted small">You can add activities after creating the trip.</p>
            </section>

            <p v-if="form.hasErrors" class="error" role="alert">{{ Object.values(form.errors).join(' ') }}</p>

            <footer>
                <template v-if="step === 1">
                    <button type="button" class="btn secondary" @click="dialog?.close()">Cancel</button>
                    <button class="btn" :disabled="!stepOneReady">Next <ArrowRight :size="16" aria-hidden="true" /></button>
                </template>
                <template v-else>
                    <button type="button" class="btn secondary" :disabled="form.processing" @click="submit(true)">Skip</button>
                    <button class="btn" :disabled="form.processing">{{ form.processing ? 'Creating…' : form.ai_prompt.trim() ? 'Create & plan with AI' : 'Create trip' }}</button>
                </template>
            </footer>
        </form>
    </dialog>
</template>

<style scoped>
.wizard{border:0;padding:0;margin:auto;width:min(480px,100vw);max-height:100dvh;border-radius:22px;box-shadow:0 24px 60px #172f2a40;color:#172f2a;overflow:visible}
.wizard::backdrop{background:#172f2a80}
.body{display:flex;flex-direction:column;gap:12px;padding:16px 18px calc(16px + env(safe-area-inset-bottom))}
header{display:flex;align-items:center;gap:10px}
.titles{flex:1;min-width:0}
.titles .eyebrow{margin-bottom:2px}
.titles h2{font-size:20px;font-weight:750;letter-spacing:-.4px}
.icon-btn{display:grid;place-items:center;width:40px;height:40px;flex-shrink:0;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.progress{height:4px;border-radius:999px;background:#e7ede1;overflow:hidden}
.progress span{display:block;height:100%;background:#72883a;border-radius:inherit;transition:width .25s}
.fields{display:grid;gap:10px}
.field{gap:5px}
.field input,.field textarea{padding:10px 12px}
.dates{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.length{display:flex;align-items:center;gap:6px;margin-top:-2px;font-size:12px;font-weight:650;color:#507044}
.length.empty{color:#9aa89a}
.locale{display:flex;gap:8px}
.mini{display:flex;align-items:center;gap:6px;padding:0 10px;height:40px;border:1px solid #d6dfd5;border-radius:10px;background:#f7f8f4;color:#507044}
.mini select{border:0;background:transparent;font:inherit;font-size:13px;font-weight:650;color:#172f2a;min-width:0}
.mini.zone{flex:1;min-width:0}
.mini.zone select{width:100%}
.hint{font-size:12px;margin-top:-4px}
.optional{font-weight:500;font-size:11px;color:#9aa89a;margin-left:4px}
.ai label{display:flex;align-items:center;gap:6px;font-size:12px;font-weight:650}
.ai textarea{border:1px solid #d6dfd5;border-radius:8px;font:inherit;font-size:14px;resize:none}
.ideas{display:flex;flex-wrap:wrap;gap:6px}
.ideas button{padding:6px 10px;border-radius:999px;border:1px solid #dfe6da;background:#f5f9ef;color:#214e3e;font:inherit;font-size:12px;font-weight:650;cursor:pointer}
.small{font-size:12px}
.error{color:#9c3e2d;font-size:13px}
footer{display:flex;gap:10px;margin-top:4px}
footer .btn{flex:1}
@media(max-width:520px){
    .wizard{width:100vw;max-width:100vw;height:100dvh;max-height:100dvh;margin:0;border-radius:0}
    .body{height:100%;padding-top:calc(14px + env(safe-area-inset-top))}
    footer{margin-top:auto}
}
@media(prefers-reduced-motion:reduce){.progress span{transition:none}}
</style>
