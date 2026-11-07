<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useForm } from '@inertiajs/vue3';
import { X } from 'lucide-vue-next';
import LocationSearch, { type PickedPlace } from '@/components/LocationSearch.vue';
import { transportOptions, type ItineraryStop } from '@/lib/itinerary';

/** Add or edit an itinerary activity, in a sheet. `open` is null when closed, 'new' to add, or the stop to edit. */
const props = defineProps<{ open: ItineraryStop | 'new' | null; tripId: number; timezone: string; browserKey: string; defaultDay: string | null }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const editing = computed(() => (props.open && props.open !== 'new' ? props.open : null));

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

watch(() => props.open, async open => {
    form.reset(); form.clearErrors();
    const item = editing.value;
    if (item) {
        Object.assign(form, {
            title: item.title, location: item.location ?? '', latitude: item.latitude ?? null, longitude: item.longitude ?? null, description: item.description ?? '',
            starts_at: localDate(item.starts_at), ends_at: localDate(item.ends_at), transport_mode: item.transport_mode ?? '',
            cost_min: pesos(item.cost_min_minor), cost_max: pesos(item.cost_max_minor), cost_note: item.cost_note ?? '',
        });
    } else if (open === 'new' && props.defaultDay) {
        // New activities start on the day being viewed.
        form.starts_at = `${props.defaultDay}T09:00`; form.ends_at = `${props.defaultDay}T10:00`;
    }
    await nextTick();
    if (open && !dialog.value?.open) dialog.value?.showModal();
    if (!open && dialog.value?.open) dialog.value.close();
});

function save() {
    const payload = form.transform(({ cost_min, cost_max, ...data }) => {
        const min = toMinor(cost_min); const max = toMinor(cost_max);
        // A single amount means an exact estimate.
        return { ...data, cost_min_minor: min ?? max, cost_max_minor: max ?? min };
    });
    const options = { preserveScroll: true, preserveState: true, onSuccess: () => emit('close') };
    if (editing.value) payload.patch(`/trips/${props.tripId}/itinerary/${editing.value.id}`, options);
    else payload.post(`/trips/${props.tripId}/itinerary`, options);
}
function backdropClick(event: MouseEvent) { if (event.target === dialog.value && !form.processing) emit('close'); }
</script>

<template>
    <dialog ref="dialog" class="form-sheet" aria-labelledby="stop-form-title" @cancel.prevent="emit('close')" @click="backdropClick">
        <form v-if="open" class="form-grid sheet-form" @submit.prevent="save">
            <div class="row spread full"><h2 id="stop-form-title">{{ editing ? 'Edit activity' : 'Add an activity' }}</h2><button type="button" class="sheet-close" aria-label="Close the form" @click="emit('close')"><X :size="18" /></button></div>
            <label class="field full">What's happening<input v-model="form.title" maxlength="200" required placeholder="e.g. Lunch at the beach" /></label>
            <div class="field full"><label for="stop-location">Where</label><LocationSearch id="stop-location" v-model="form.location" v-model:pinned="pinned" :browser-key="browserKey" :maxlength="200" placeholder="Search a place" /></div>
            <label class="field">Starts ({{ timezone }})<input v-model="form.starts_at" type="datetime-local" required /></label>
            <label class="field">Ends ({{ timezone }})<input v-model="form.ends_at" type="datetime-local" required /></label>
            <label class="field full">Getting there from the previous activity<select v-model="form.transport_mode"><option value="">Starting point / decide later</option><option v-for="option in transportOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
            <fieldset class="field full cost-fields">
                <legend>Estimated cost per person</legend>
                <div class="cost-inputs"><input v-model="form.cost_min" type="number" min="0" step="1" inputmode="numeric" placeholder="From" aria-label="Minimum cost per person" /><span aria-hidden="true">–</span><input v-model="form.cost_max" type="number" min="0" step="1" inputmode="numeric" placeholder="To" aria-label="Maximum cost per person" /></div>
                <input v-model="form.cost_note" maxlength="255" placeholder="What it covers, e.g. Lunch, entrance fee" aria-label="What the cost covers" />
            </fieldset>
            <label class="field full">Notes<textarea v-model="form.description" maxlength="4000" rows="3" placeholder="Booking details, meeting point, or reminders" /></label>
            <ul v-if="Object.keys(form.errors).length" class="full form-errors" role="alert"><li v-for="(message, field) in form.errors" :key="field">{{ message }}</li></ul>
            <div class="row full sheet-buttons"><button class="btn" :disabled="form.processing">{{ form.processing ? 'Saving…' : editing ? 'Save activity' : 'Add activity' }}</button><button class="btn secondary" type="button" :disabled="form.processing" @click="emit('close')">Cancel</button></div>
        </form>
    </dialog>
</template>

<style scoped>
.form-sheet{border:0;padding:0;margin:auto auto 0;width:100%;max-width:640px;max-height:92dvh;border-radius:22px 22px 0 0;background:#fffefb;color:#172f2a;box-shadow:0 -10px 40px #1d33281f}
.form-sheet::backdrop{background:#10221a66}
.sheet-form{padding:20px 20px calc(20px + env(safe-area-inset-bottom));overflow-y:auto;max-height:inherit}
.sheet-form h2{font-size:20px;font-weight:750;letter-spacing:-.4px}
.sheet-close{display:grid;place-items:center;width:44px;height:44px;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.cost-fields{border:0;padding:0;margin:0;min-width:0}
.cost-fields legend{font-size:12px;font-weight:650;margin-bottom:7px}
.cost-inputs{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.cost-inputs input{min-width:0}
.form-errors{color:#ae4e36;font-size:13px}
.sheet-buttons .btn{min-height:44px}
@media(min-width:768px){.form-sheet{margin:auto;border-radius:22px}}
</style>
