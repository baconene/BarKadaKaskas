<script setup lang="ts">
import { router, useForm } from '@inertiajs/vue3';
import { Camera, HeartPulse, MessageCircle, Phone, Sparkles, Trash2, UserRound, X } from 'lucide-vue-next';
import { computed, onMounted, ref } from 'vue';
import AvatarEditor from '@/components/account/AvatarEditor.vue';

/** A traveller's contact details and emergency contact; your own can be edited here, including your photo. */
export type TravelerUser = {
    id?: number; name: string; phone?: string | null; other_contact?: string | null; avatar_url?: string | null;
    emergency_contact_name?: string | null; emergency_contact_relationship?: string | null; emergency_contact_phone?: string | null;
};
const props = defineProps<{ user: TravelerUser; isMe: boolean; isOrganizer: boolean; startEditing?: boolean }>();
const emit = defineEmits<{ close: [] }>();

const dialog = ref<HTMLDialogElement | null>(null);
const editing = ref(props.isMe && !!props.startEditing);
const form = useForm({
    phone: props.user.phone ?? '', other_contact: props.user.other_contact ?? '',
    emergency_contact_name: props.user.emergency_contact_name ?? '', emergency_contact_relationship: props.user.emergency_contact_relationship ?? '', emergency_contact_phone: props.user.emergency_contact_phone ?? '',
});
const uploading = ref(false);
const photoError = ref('');
onMounted(() => dialog.value?.showModal());

const initials = computed(() => props.user.name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]!.toUpperCase()).join(''));
const tel = (value: string) => `tel:${value.replace(/[^0-9+]/g, '')}`;
const hasEmergency = computed(() => !!props.user.emergency_contact_name || !!props.user.emergency_contact_phone);

function save() {
    form.patch('/me/profile', { preserveScroll: true, preserveState: true, onSuccess: () => { editing.value = false; } });
}
// New photos (and "Edit") open the built-in editor with crop, filters and AI styles.
const editingPhoto = ref<string | null>(null);
function pickPhoto(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) { photoError.value = ''; editingPhoto.value = URL.createObjectURL(file); }
}
function closeEditor() { if (editingPhoto.value?.startsWith('blob:')) URL.revokeObjectURL(editingPhoto.value); editingPhoto.value = null; }
function removePhoto() {
    uploading.value = true;
    router.delete('/me/avatar', { preserveScroll: true, preserveState: true, onFinish: () => { uploading.value = false; } });
}
</script>

<template>
    <dialog ref="dialog" class="traveler-dialog" :aria-label="user.name" @close="emit('close')" @click.self="dialog?.close()">
        <div class="body">
            <button type="button" class="close" aria-label="Close" @click="dialog?.close()"><X :size="18" /></button>
            <div class="hero">
                <div class="photo">
                    <img v-if="user.avatar_url" :src="user.avatar_url" alt="" />
                    <span v-else>{{ initials }}</span>
                    <label v-if="isMe" class="photo-edit" :class="{ busy: uploading }" :aria-label="user.avatar_url ? 'Change profile picture' : 'Add profile picture'">
                        <Camera :size="16" aria-hidden="true" />
                        <input type="file" accept="image/*" class="sr-only" :disabled="uploading" @change="pickPhoto" />
                    </label>
                </div>
                <h2>{{ user.name }}</h2>
                <p class="tags"><span v-if="isMe" class="tag">You</span><span v-if="isOrganizer" class="tag organizer">Organizer</span></p>
                <p v-if="uploading" class="muted small">Updating photo…</p>
                <p v-else-if="isMe && user.avatar_url" class="photo-links"><button type="button" class="link small edit-link" @click="editingPhoto = user.avatar_url ?? null"><Sparkles :size="13" aria-hidden="true" /> Edit &amp; AI styles</button><button type="button" class="link small" @click="removePhoto"><Trash2 :size="13" aria-hidden="true" /> Remove</button></p>
                <p v-if="photoError" class="error small" role="alert">{{ photoError }}</p>
            </div>

            <template v-if="!editing">
                <section class="block">
                    <h3><UserRound :size="15" aria-hidden="true" /> Contact</h3>
                    <a v-if="user.phone" :href="tel(user.phone)" class="contact-row"><Phone :size="17" aria-hidden="true" /><span><strong>{{ user.phone }}</strong><small>Tap to call</small></span></a>
                    <p v-if="user.other_contact" class="contact-row static"><MessageCircle :size="17" aria-hidden="true" /><span><strong>{{ user.other_contact }}</strong><small>Other contact</small></span></p>
                    <p v-if="!user.phone && !user.other_contact" class="muted small">{{ isMe ? 'Add a number so your barkada can reach you.' : 'No contact details yet.' }}</p>
                </section>
                <section class="block emergency">
                    <h3><HeartPulse :size="15" aria-hidden="true" /> Emergency contact</h3>
                    <template v-if="hasEmergency">
                        <p class="em-name"><strong>{{ user.emergency_contact_name }}</strong><span v-if="user.emergency_contact_relationship"> · {{ user.emergency_contact_relationship }}</span></p>
                        <a v-if="user.emergency_contact_phone" :href="tel(user.emergency_contact_phone)" class="contact-row danger"><Phone :size="17" aria-hidden="true" /><span><strong>{{ user.emergency_contact_phone }}</strong><small>Call in an emergency</small></span></a>
                    </template>
                    <p v-else class="muted small">{{ isMe ? 'Add someone the group can call if something happens to you.' : 'Not added yet.' }}</p>
                </section>
                <button v-if="isMe" type="button" class="btn wide" @click="editing = true">Edit my details</button>
                <p class="muted small privacy">Only people on your trips can see these details.</p>
            </template>

            <form v-else class="edit" @submit.prevent="save">
                <label class="field">Mobile number<input v-model="form.phone" type="tel" inputmode="tel" autocomplete="tel" placeholder="+63 917 123 4567" maxlength="40" /></label>
                <label class="field">Other contact<input v-model="form.other_contact" placeholder="e.g. Messenger: @jamie, Viber" maxlength="120" /></label>
                <h3 class="em-title"><HeartPulse :size="15" aria-hidden="true" /> Emergency contact</h3>
                <label class="field">Name<input v-model="form.emergency_contact_name" autocomplete="off" placeholder="e.g. Maria Cruz" maxlength="120" /></label>
                <label class="field">Relationship<input v-model="form.emergency_contact_relationship" placeholder="e.g. Mother, Partner" maxlength="60" /></label>
                <label class="field">Phone<input v-model="form.emergency_contact_phone" type="tel" inputmode="tel" placeholder="+63 917 765 4321" maxlength="40" /></label>
                <p v-if="form.hasErrors" class="error small" role="alert">{{ Object.values(form.errors).join(' ') }}</p>
                <div class="actions"><button type="button" class="btn secondary" :disabled="form.processing" @click="editing = false">Cancel</button><button class="btn" :disabled="form.processing || !form.isDirty">{{ form.processing ? 'Saving…' : 'Save' }}</button></div>
            </form>
        </div>
        <AvatarEditor v-if="editingPhoto" :source="editingPhoto" @close="closeEditor" />
    </dialog>
</template>

<style scoped>
.traveler-dialog{border:0;border-radius:22px;padding:0;width:min(440px,calc(100vw - 24px));max-height:calc(100dvh - 24px);box-shadow:0 24px 60px #172f2a40;color:#172f2a}
.traveler-dialog::backdrop{background:#172f2a66}
.body{position:relative;display:grid;gap:14px;padding:22px}
.close{position:absolute;top:12px;right:12px;display:grid;place-items:center;width:38px;height:38px;border-radius:12px;border:1px solid #dfe6da;background:#fff;color:#214e3e;cursor:pointer}
.hero{display:grid;justify-items:center;gap:4px;text-align:center}
.photo{position:relative;width:96px;height:96px;border-radius:50%;background:#e3edd5;color:#214e3e;display:grid;place-items:center;font-size:32px;font-weight:800}
/* Absolutely positioned so tall photos fill the circle instead of stretching the grid row. */
.photo img{position:absolute;inset:0;width:100%;height:100%;border-radius:50%;object-fit:cover}
.photo-links{display:flex;gap:14px}
.edit-link{color:#214e3e}
.photo-edit{position:absolute;right:-2px;bottom:-2px;display:grid;place-items:center;width:34px;height:34px;border-radius:50%;background:#214e3e;color:#fff;border:3px solid #fff;cursor:pointer}
.photo-edit.busy{opacity:.6;pointer-events:none}
.hero h2{font-size:21px;font-weight:750;margin-top:8px}
.tags{display:flex;gap:6px}
.tag{font-size:11px;font-weight:700;padding:3px 8px;border-radius:999px;background:#edf2e6;color:#526936}
.tag.organizer{background:#fff3d6;color:#926815}
.block{display:grid;gap:8px;padding:14px;border-radius:16px;background:#f7f8f4;border:1px solid #e3e8da}
.block h3,.em-title{display:flex;align-items:center;gap:6px;font-size:13px;font-weight:750;color:#507044;text-transform:uppercase;letter-spacing:.6px}
.emergency{background:#fff7f4;border-color:#f0d6cc}
.emergency h3{color:#9c3e2d}
.contact-row{display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:12px;background:#fff;border:1px solid #e3e8da;color:#214e3e;text-decoration:none}
.contact-row.static{margin:0}
.contact-row span{display:grid}
.contact-row small{font-size:12px;color:#738178}
.contact-row.danger{color:#9c3e2d;border-color:#f0d6cc}
.em-name{font-size:15px}
.small{font-size:12px}
.link{display:inline-flex;align-items:center;gap:4px;border:0;background:none;color:#9c3e2d;cursor:pointer;font:inherit}
.error{color:#9c3e2d}
.privacy{text-align:center}
.wide{width:100%;min-height:48px}
.edit{display:grid;gap:12px}
.actions{display:flex;justify-content:flex-end;gap:10px}
</style>
