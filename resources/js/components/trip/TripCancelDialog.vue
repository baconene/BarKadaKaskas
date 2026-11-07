<script setup lang="ts">
import { useForm } from '@inertiajs/vue3';
import { CircleX, EyeOff } from 'lucide-vue-next';
import { onMounted, ref } from 'vue';

/** Organizer-only confirmation for cancelling a trip, with an option to hide it from everyone's trip list. */
const props = defineProps<{ tripId: number; tripName: string }>();
const emit = defineEmits<{ close: [] }>();
const form = useForm({ reason: '', hide: false });
const dialog = ref<HTMLDialogElement | null>(null);

onMounted(() => dialog.value?.showModal());
function submit() {
    form.post(`/trips/${props.tripId}/cancel`, { preserveScroll: true, onSuccess: () => emit('close') });
}
</script>

<template>
    <dialog ref="dialog" class="cancel-dialog" aria-labelledby="cancel-trip-title" @close="emit('close')" @click.self="emit('close')">
        <form method="dialog" @submit.prevent="submit">
            <div class="icon" aria-hidden="true"><CircleX :size="26" /></div>
            <h2 id="cancel-trip-title">Cancel “{{ tripName }}”?</h2>
            <p class="muted">The plan and new expenses are locked. Everyone can still see what was spent and settle up. You can reopen the trip later.</p>
            <label class="field">Reason (optional)<textarea v-model="form.reason" rows="2" maxlength="500" placeholder="e.g. Typhoon warning, moving to next month" /></label>
            <label class="hide-option">
                <input v-model="form.hide" type="checkbox" />
                <span><strong><EyeOff :size="15" aria-hidden="true" /> Hide this trip</strong><small>It won't show in anyone's trip list. Find it later under “Hidden trips”.</small></span>
            </label>
            <p v-if="form.errors.reason || form.hasErrors" class="error" role="alert">{{ Object.values(form.errors).join(' ') }}</p>
            <div class="actions">
                <button type="button" class="btn secondary" :disabled="form.processing" @click="emit('close')">Keep trip</button>
                <button type="submit" class="btn danger-solid" :disabled="form.processing">{{ form.processing ? 'Cancelling…' : form.hide ? 'Cancel & hide trip' : 'Cancel trip' }}</button>
            </div>
        </form>
    </dialog>
</template>

<style scoped>
.cancel-dialog{border:0;border-radius:20px;padding:0;width:min(440px,calc(100vw - 32px));box-shadow:0 24px 60px #172f2a40;color:#172f2a}
.cancel-dialog::backdrop{background:#172f2a66}
form{display:grid;gap:14px;padding:24px}
.icon{display:grid;place-items:center;width:48px;height:48px;border-radius:14px;background:#fff0eb;color:#9c3e2d}
.travel-app .cancel-dialog h2,.cancel-dialog h2{font-size:20px;font-weight:750;letter-spacing:-.4px}
textarea{border:1px solid #d6dfd5;border-radius:10px;padding:10px 12px;font:inherit;font-size:14px;resize:vertical}
.hide-option{display:flex;gap:12px;align-items:flex-start;padding:14px;border:1px solid #e0e7dc;border-radius:14px;cursor:pointer}
.hide-option:has(input:checked){border-color:#9cb865;background:#f5f9ef}
.hide-option input{width:20px;height:20px;margin-top:2px;accent-color:#214e3e}
.hide-option strong{display:flex;align-items:center;gap:6px;font-size:14px}
.hide-option small{display:block;margin-top:3px;font-size:12px;color:#738178;line-height:1.5}
.error{color:#9c3e2d;font-size:13px}
.actions{display:flex;gap:10px;justify-content:flex-end;flex-wrap:wrap}
.actions .btn{min-height:46px}
.danger-solid{background:#9c3e2d}
@media(max-width:480px){.actions{flex-direction:column-reverse}.actions .btn{width:100%}}
</style>
