<script setup lang="ts">
import { router, useForm } from '@inertiajs/vue3';
import { ImagePlus, Sparkles, Trash2, X } from 'lucide-vue-next';
import PhotoEditor from '@/components/PhotoEditor.vue';
import { CURRENCIES } from '@/lib/regions';
import { computed, onMounted, ref } from 'vue';

/** Organizer-only editor for the trip's details and cover photo. */
type EditableTrip = { id: number; name: string; destination: string; description: string | null; starts_on: string; ends_on: string; timezone: string; currency_code: string; cover_url?: string | null };
const props = defineProps<{ trip: EditableTrip; hasExpenses: boolean }>();
const emit = defineEmits<{ close: [] }>();

const dialog = ref<HTMLDialogElement | null>(null);
const form = useForm({
    name: props.trip.name, destination: props.trip.destination, description: props.trip.description ?? '',
    starts_on: String(props.trip.starts_on).slice(0, 10), ends_on: String(props.trip.ends_on).slice(0, 10),
    timezone: props.trip.timezone, currency_code: props.trip.currency_code,
});
const timezones = computed(() => {
    const list = ['Asia/Manila', 'Asia/Singapore', 'Asia/Tokyo', 'Asia/Seoul', 'Asia/Hong_Kong', 'Asia/Bangkok', 'Asia/Dubai', 'Australia/Sydney', 'Europe/London', 'Europe/Paris', 'America/New_York', 'America/Los_Angeles', 'UTC'];
    return list.includes(form.timezone) ? list : [form.timezone, ...list];
});

const preview = ref<string | null>(props.trip.cover_url ?? null);
const uploading = ref(false);
const coverError = ref('');
const fileInput = ref<HTMLInputElement | null>(null);

onMounted(() => dialog.value?.showModal());

// New covers (and "Edit & AI styles" on the current one) open the built-in editor with a wide 16:9 crop.
const editingCover = ref<string | null>(null);
function pickCover(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) { coverError.value = ''; editingCover.value = URL.createObjectURL(file); }
}
function closeEditor() { if (editingCover.value?.startsWith('blob:')) URL.revokeObjectURL(editingCover.value); editingCover.value = null; }
function uploadCover(photo: Blob) {
    uploading.value = true;
    preview.value = URL.createObjectURL(photo);
    router.post(`/trips/${props.trip.id}/cover`, { cover: new File([photo], 'cover.jpg', { type: 'image/jpeg' }) }, {
        forceFormData: true, preserveScroll: true, preserveState: true,
        onError: errors => { coverError.value = Object.values(errors).join(' '); preview.value = props.trip.cover_url ?? null; },
        onFinish: () => { uploading.value = false; },
    });
}

function removeCover() {
    uploading.value = true;
    router.delete(`/trips/${props.trip.id}/cover`, { preserveScroll: true, preserveState: true, onSuccess: () => { preview.value = null; }, onFinish: () => { uploading.value = false; } });
}

function save() {
    form.patch(`/trips/${props.trip.id}`, { preserveScroll: true, onSuccess: () => dialog.value?.close() });
}
</script>

<template>
    <dialog ref="dialog" class="edit-dialog" aria-labelledby="edit-trip-title" @close="emit('close')">
        <form @submit.prevent="save">
            <header>
                <h2 id="edit-trip-title">Edit trip</h2>
                <button type="button" class="close" aria-label="Close" @click="dialog?.close()"><X :size="18" /></button>
            </header>

            <div class="cover" :class="{ empty: !preview }">
                <img v-if="preview" :src="preview" alt="Trip cover photo" />
                <div v-else class="placeholder"><ImagePlus :size="28" aria-hidden="true" /><span>Add a cover photo</span><small>Shown on everyone's trip card</small></div>
                <div class="cover-actions">
                    <label class="btn secondary small" :class="{ disabled: uploading }">
                        <ImagePlus :size="15" aria-hidden="true" /> {{ uploading ? 'Uploading…' : preview ? 'New photo' : 'Upload photo' }}
                        <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" class="sr-only" :disabled="uploading" @change="pickCover" />
                    </label>
                    <button v-if="preview && trip.cover_url" type="button" class="btn secondary small" :disabled="uploading" @click="editingCover = trip.cover_url ?? null"><Sparkles :size="15" aria-hidden="true" /> Edit</button>
                    <button v-if="preview" type="button" class="btn secondary small" :disabled="uploading" aria-label="Remove cover photo" @click="removeCover"><Trash2 :size="15" /></button>
                </div>
            </div>
            <p v-if="coverError" class="error" role="alert">{{ coverError }}</p>

            <div class="form-grid">
                <label class="field full">Trip name<input v-model="form.name" required maxlength="120" /></label>
                <label class="field full">Destination<input v-model="form.destination" required maxlength="200" /></label>
                <label class="field">Starts<input v-model="form.starts_on" type="date" required /></label>
                <label class="field">Ends<input v-model="form.ends_on" type="date" :min="form.starts_on" required /></label>
                <label class="field">Currency
                    <select v-model="form.currency_code" :disabled="hasExpenses"><option v-for="c in CURRENCIES" :key="c">{{ c }}</option></select>
                    <small v-if="hasExpenses" class="hint">Locked after expenses are recorded.</small>
                </label>
                <label class="field">Time zone<select v-model="form.timezone"><option v-for="z in timezones" :key="z" :value="z">{{ z.replace('_', ' ') }}</option></select></label>
                <label class="field full">About this trip<textarea v-model="form.description" rows="3" maxlength="4000" /></label>
            </div>
            <p v-if="form.hasErrors" class="error" role="alert">{{ Object.values(form.errors).join(' ') }}</p>

            <div class="actions">
                <button type="button" class="btn secondary" :disabled="form.processing" @click="dialog?.close()">Cancel</button>
                <button type="submit" class="btn" :disabled="form.processing || !form.isDirty">{{ form.processing ? 'Saving…' : 'Save changes' }}</button>
            </div>
        </form>
        <PhotoEditor v-if="editingCover" :source="editingCover" :aspect="16 / 9" :output-size="1600" subject="photo" title="Edit cover photo" confirm-label="Use as cover" @done="uploadCover" @close="closeEditor" />
    </dialog>
</template>

<style scoped>
.edit-dialog{border:0;border-radius:20px;padding:0;width:min(560px,calc(100vw - 24px));max-height:calc(100dvh - 24px);box-shadow:0 24px 60px #172f2a40;color:#172f2a}
.edit-dialog::backdrop{background:#172f2a66}
form{display:grid;gap:16px;padding:22px}
header{display:flex;align-items:center;justify-content:space-between}
.travel-app .edit-dialog h2,.edit-dialog h2{font-size:20px;font-weight:750;letter-spacing:-.4px}
.close{display:grid;place-items:center;width:40px;height:40px;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.cover{position:relative;height:180px;border-radius:16px;overflow:hidden;background:#dce7cb}
.cover img{width:100%;height:100%;object-fit:cover}
.cover.empty{border:2px dashed #b9c9a6;background:#f2f6ec}
.placeholder{height:100%;display:grid;place-content:center;justify-items:center;gap:4px;color:#507044;font-weight:650}
.placeholder small{font-weight:400;color:#738178;font-size:12px}
.cover-actions{position:absolute;right:10px;bottom:10px;display:flex;gap:8px}
.btn.small{padding:8px 12px;font-size:13px;min-height:38px;box-shadow:0 2px 8px #172f2a26}
.btn.disabled{opacity:.6;pointer-events:none}
.hint{font-weight:400;color:#738178;font-size:11px}
textarea{border:1px solid #d6dfd5;border-radius:8px;padding:10px 12px;font:inherit;font-size:14px;resize:vertical}
.error{color:#9c3e2d;font-size:13px}
.actions{display:flex;gap:10px;justify-content:flex-end}
.actions .btn{min-height:46px}
@media(max-width:520px){.form-grid{grid-template-columns:1fr 1fr}.actions{flex-direction:column-reverse}.actions .btn{width:100%}}
</style>
