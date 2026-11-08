<script setup lang="ts">
import { Head, Link, router, useForm, usePage } from '@inertiajs/vue3';
import { Camera, HeartPulse, Phone, Sparkles, Trash2 } from 'lucide-vue-next';
import { computed, ref } from 'vue';
import AccountLayout from '@/layouts/AccountLayout.vue';
import AvatarEditor from '@/components/account/AvatarEditor.vue';
import { type SharedData } from '@/types';

type ProfileUser = {
    name: string; email: string; email_verified_at?: string | null; avatar_url?: string | null; phone?: string | null; other_contact?: string | null;
    emergency_contact_name?: string | null; emergency_contact_relationship?: string | null; emergency_contact_phone?: string | null;
};
defineProps<{ mustVerifyEmail: boolean; status?: string }>();
const page = usePage<SharedData>();
const user = computed(() => page.props.auth.user as unknown as ProfileUser);
const initials = computed(() => user.value.name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]!.toUpperCase()).join(''));

// Profile picture: every new or existing photo goes through the built-in editor before it is saved.
const editing = ref<string | null>(null);
const uploading = ref(false);
const photoError = ref('');
function pickPhoto(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    photoError.value = '';
    editing.value = URL.createObjectURL(file);
}
function closeEditor() { if (editing.value?.startsWith('blob:')) URL.revokeObjectURL(editing.value); editing.value = null; }
function removePhoto() {
    uploading.value = true;
    router.delete('/me/avatar', { preserveScroll: true, onFinish: () => { uploading.value = false; } });
}

// Name and email
const account = useForm({ name: user.value.name, email: user.value.email });
const accountSaved = ref(false);
function saveAccount() {
    account.patch(route('profile.update'), { preserveScroll: true, onSuccess: () => { accountSaved.value = true; setTimeout(() => { accountSaved.value = false; }, 2500); } });
}

// Contact and emergency contact
const contact = useForm({
    phone: user.value.phone ?? '', other_contact: user.value.other_contact ?? '',
    emergency_contact_name: user.value.emergency_contact_name ?? '', emergency_contact_relationship: user.value.emergency_contact_relationship ?? '', emergency_contact_phone: user.value.emergency_contact_phone ?? '',
});
const contactSaved = ref(false);
function saveContact() {
    contact.patch('/me/profile', { preserveScroll: true, onSuccess: () => { contact.defaults(); contactSaved.value = true; setTimeout(() => { contactSaved.value = false; }, 2500); } });
}

// Delete account
const deleting = ref(false);
const removal = useForm({ password: '' });
function deleteAccount() {
    removal.delete(route('profile.destroy'), { preserveScroll: true, onFinish: () => removal.reset() });
}
</script>

<template>
    <Head title="Profile" />
    <AccountLayout active="profile">
        <section class="card photo-card" aria-labelledby="photo-title">
            <div class="photo">
                <img v-if="user.avatar_url" :src="user.avatar_url" alt="Your profile picture" />
                <span v-else aria-hidden="true">{{ initials }}</span>
            </div>
            <div class="photo-body">
                <h2 id="photo-title">Profile picture</h2>
                <p class="muted">Shown to your barkada on trips, photos and expenses. Crop it, add a filter, or restyle it with AI.</p>
                <div class="row">
                    <label class="btn" :class="{ disabled: uploading }"><Camera :size="16" aria-hidden="true" /> {{ uploading ? 'Updating…' : user.avatar_url ? 'New photo' : 'Upload photo' }}<input type="file" accept="image/*" class="sr-only" :disabled="uploading" @change="pickPhoto" /></label>
                    <button v-if="user.avatar_url" type="button" class="btn secondary" :disabled="uploading" @click="editing = user.avatar_url ?? null"><Sparkles :size="15" aria-hidden="true" /> Edit &amp; AI styles</button>
                    <button v-if="user.avatar_url" type="button" class="btn secondary" :disabled="uploading" aria-label="Remove photo" @click="removePhoto"><Trash2 :size="15" aria-hidden="true" /></button>
                </div>
                <p v-if="photoError" class="error" role="alert">{{ photoError }}</p>
            </div>
        </section>

        <form class="card form-grid" aria-labelledby="account-title" @submit.prevent="saveAccount">
            <div class="full"><h2 id="account-title">Name and email</h2><p class="muted">Your email is private; it's only used to sign in.</p></div>
            <label class="field">Name<input v-model="account.name" required autocomplete="name" maxlength="255" /></label>
            <label class="field">Email<input v-model="account.email" type="email" required autocomplete="username" /></label>
            <p v-if="account.hasErrors" class="error full" role="alert">{{ Object.values(account.errors).join(' ') }}</p>
            <p v-if="mustVerifyEmail && !user.email_verified_at" class="muted full">Your email address is unverified. <Link :href="route('verification.send')" method="post" as="button" class="link">Resend the verification email</Link>. <span v-if="status === 'verification-link-sent'" class="ok">A new link was sent.</span></p>
            <div class="row full"><button class="btn" :disabled="account.processing || !account.isDirty">{{ account.processing ? 'Saving…' : 'Save' }}</button><span v-if="accountSaved" class="ok" role="status">Saved</span></div>
        </form>

        <form class="card form-grid" aria-labelledby="contact-title" @submit.prevent="saveContact">
            <div class="full"><h2 id="contact-title"><Phone :size="18" aria-hidden="true" /> Contact</h2><p class="muted">Only people on your trips can see these.</p></div>
            <label class="field">Mobile number<input v-model="contact.phone" type="tel" inputmode="tel" autocomplete="tel" placeholder="+63 917 123 4567" maxlength="40" /></label>
            <label class="field">Other contact<input v-model="contact.other_contact" placeholder="e.g. Messenger: @jamie, Viber" maxlength="120" /></label>
            <div class="full"><h2 class="emergency"><HeartPulse :size="18" aria-hidden="true" /> Emergency contact</h2><p class="muted">Someone your group can call if something happens to you.</p></div>
            <label class="field">Name<input v-model="contact.emergency_contact_name" placeholder="e.g. Maria Cruz" maxlength="120" /></label>
            <label class="field">Relationship<input v-model="contact.emergency_contact_relationship" placeholder="e.g. Mother, Partner" maxlength="60" /></label>
            <label class="field">Phone<input v-model="contact.emergency_contact_phone" type="tel" inputmode="tel" placeholder="+63 917 765 4321" maxlength="40" /></label>
            <p v-if="contact.hasErrors" class="error full" role="alert">{{ Object.values(contact.errors).join(' ') }}</p>
            <div class="row full"><button class="btn" :disabled="contact.processing || !contact.isDirty">{{ contact.processing ? 'Saving…' : 'Save contact details' }}</button><span v-if="contactSaved" class="ok" role="status">Saved</span></div>
        </form>

        <section class="card danger-zone" aria-labelledby="delete-title">
            <h2 id="delete-title">Delete account</h2>
            <p class="muted">Permanently removes your account. Accounts with trip history can't be deleted yet, so shared expenses stay correct for everyone.</p>
            <button v-if="!deleting" type="button" class="btn danger" @click="deleting = true">Delete my account…</button>
            <form v-else class="delete-form" @submit.prevent="deleteAccount">
                <label class="field">Enter your password to confirm<input v-model="removal.password" type="password" autocomplete="current-password" required /></label>
                <p v-if="removal.errors.password" class="error" role="alert">{{ removal.errors.password }}</p>
                <div class="row"><button class="btn danger" :disabled="removal.processing">Delete account</button><button type="button" class="btn secondary" @click="deleting = false; removal.reset(); removal.clearErrors()">Cancel</button></div>
            </form>
        </section>
        <AvatarEditor v-if="editing" :source="editing" @close="closeEditor" />
    </AccountLayout>
</template>

<style scoped>
.card h2{display:flex;align-items:center;gap:8px}
.photo-card{display:flex;gap:20px;align-items:center}
.photo{position:relative;width:96px;height:96px;flex-shrink:0;border-radius:50%;background:#e3edd5;color:#214e3e;display:grid;place-items:center;font-size:32px;font-weight:800;overflow:hidden}
/* Absolutely positioned so tall photos fill the circle instead of stretching the grid row. */
.photo img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.photo-body{display:grid;gap:8px}
.btn.disabled{opacity:.6;pointer-events:none}
.emergency{color:#9c3e2d}
.error{color:#9c3e2d;font-size:13px}
.ok{color:#276c47;font-size:13px;font-weight:650}
.link{border:0;background:none;color:#214e3e;text-decoration:underline;font:inherit;cursor:pointer;padding:0}
.danger-zone{display:grid;gap:10px;border-color:#f0d6cc}
.delete-form{display:grid;gap:10px}
@media(max-width:520px){.photo-card{flex-direction:column;text-align:center}.photo-body .row{justify-content:center}}
</style>
