<script setup lang="ts">
import { Head, Link, router, useForm, usePage } from '@inertiajs/vue3';
import { Camera, Check, HeartPulse, Phone, Sparkles, Trash2, UserRound } from 'lucide-vue-next';
import { computed, ref } from 'vue';
import AccountLayout from '@/layouts/AccountLayout.vue';
import PhotoEditor from '@/components/PhotoEditor.vue';
import { saveAvatar } from '@/lib/uploads';
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

// Name, email, contact and emergency contact are edited together and saved with one button (two endpoints).
const account = useForm({ name: user.value.name, email: user.value.email });
const contact = useForm({
    phone: user.value.phone ?? '', other_contact: user.value.other_contact ?? '',
    emergency_contact_name: user.value.emergency_contact_name ?? '', emergency_contact_relationship: user.value.emergency_contact_relationship ?? '', emergency_contact_phone: user.value.emergency_contact_phone ?? '',
});
const dirty = computed(() => account.isDirty || contact.isDirty);
const saving = computed(() => account.processing || contact.processing);
const errors = computed(() => [...Object.values(account.errors), ...Object.values(contact.errors)]);
const saved = ref(false);
function done() { saved.value = true; setTimeout(() => { saved.value = false; }, 2500); }
function saveContact() {
    if (!contact.isDirty) { done(); return; }
    contact.patch('/me/profile', { preserveScroll: true, onSuccess: () => { contact.defaults(); done(); } });
}
function save() {
    if (!account.isDirty) { saveContact(); return; }
    account.patch(route('profile.update'), { preserveScroll: true, onSuccess: () => { account.defaults(); saveContact(); } });
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
    <AccountLayout active="profile" wide>
        <div class="profile">
            <aside class="side">
                <section class="card identity" aria-labelledby="photo-title">
                    <div class="photo">
                        <img v-if="user.avatar_url" :src="user.avatar_url" alt="Your profile picture" />
                        <span v-else aria-hidden="true">{{ initials }}</span>
                    </div>
                    <div class="who">
                        <h2 id="photo-title">{{ user.name }}</h2>
                        <p class="muted">{{ user.email }}</p>
                    </div>
                    <div class="photo-actions">
                        <label class="btn" :class="{ disabled: uploading }"><Camera :size="15" aria-hidden="true" /> {{ uploading ? 'Updating…' : user.avatar_url ? 'New photo' : 'Upload photo' }}<input type="file" accept="image/*" class="sr-only" :disabled="uploading" @change="pickPhoto" /></label>
                        <button v-if="user.avatar_url" type="button" class="btn secondary" :disabled="uploading" @click="editing = user.avatar_url ?? null"><Sparkles :size="15" aria-hidden="true" /> Edit &amp; AI</button>
                        <button v-if="user.avatar_url" type="button" class="btn secondary icon" :disabled="uploading" aria-label="Remove photo" title="Remove photo" @click="removePhoto"><Trash2 :size="15" aria-hidden="true" /></button>
                    </div>
                    <p class="muted hint">Shown to your barkada on trips, photos and expenses.</p>
                    <p v-if="photoError" class="error" role="alert">{{ photoError }}</p>
                </section>

                <section class="card danger-zone" aria-labelledby="delete-title">
                    <h3 id="delete-title">Delete account</h3>
                    <p class="muted">Permanent. Accounts with trip history can’t be deleted yet, so shared expenses stay correct.</p>
                    <button v-if="!deleting" type="button" class="btn danger small" @click="deleting = true">Delete my account…</button>
                    <form v-else class="delete-form" @submit.prevent="deleteAccount">
                        <label class="field">Password to confirm<input v-model="removal.password" type="password" autocomplete="current-password" required /></label>
                        <p v-if="removal.errors.password" class="error" role="alert">{{ removal.errors.password }}</p>
                        <div class="row"><button class="btn danger small" :disabled="removal.processing">Delete account</button><button type="button" class="btn secondary small" @click="deleting = false; removal.reset(); removal.clearErrors()">Cancel</button></div>
                    </form>
                </section>
            </aside>

            <form class="card details" aria-label="Your details" @submit.prevent="save">
                <fieldset>
                    <legend><UserRound :size="16" aria-hidden="true" /> Name and email <span class="muted">Your email is private; it’s only used to sign in.</span></legend>
                    <div class="grid two">
                        <label class="field">Name<input v-model="account.name" required autocomplete="name" maxlength="255" /></label>
                        <label class="field">Email<input v-model="account.email" type="email" required autocomplete="username" /></label>
                    </div>
                    <p v-if="mustVerifyEmail && !user.email_verified_at" class="muted note">Your email address is unverified. <Link :href="route('verification.send')" method="post" as="button" class="link">Resend the verification email</Link>. <span v-if="status === 'verification-link-sent'" class="ok">A new link was sent.</span></p>
                </fieldset>

                <fieldset>
                    <legend><Phone :size="16" aria-hidden="true" /> Contact <span class="muted">Only people on your trips can see these.</span></legend>
                    <div class="grid two">
                        <label class="field">Mobile number<input v-model="contact.phone" type="tel" inputmode="tel" autocomplete="tel" placeholder="+63 917 123 4567" maxlength="40" /></label>
                        <label class="field">Other contact<input v-model="contact.other_contact" placeholder="e.g. Messenger: @jamie, Viber" maxlength="120" /></label>
                    </div>
                </fieldset>

                <fieldset>
                    <legend class="emergency"><HeartPulse :size="16" aria-hidden="true" /> Emergency contact <span class="muted">Someone your group can call if something happens to you.</span></legend>
                    <div class="grid three">
                        <label class="field">Name<input v-model="contact.emergency_contact_name" placeholder="e.g. Maria Cruz" maxlength="120" /></label>
                        <label class="field">Relationship<input v-model="contact.emergency_contact_relationship" placeholder="e.g. Mother, Partner" maxlength="60" /></label>
                        <label class="field">Phone<input v-model="contact.emergency_contact_phone" type="tel" inputmode="tel" placeholder="+63 917 765 4321" maxlength="40" /></label>
                    </div>
                </fieldset>

                <div class="save-bar">
                    <p v-if="errors.length" class="error" role="alert">{{ errors.join(' ') }}</p>
                    <span v-else-if="saved" class="ok" role="status"><Check :size="15" aria-hidden="true" /> Saved</span>
                    <span v-else-if="dirty" class="muted unsaved">Unsaved changes</span>
                    <button class="btn" :disabled="saving || !dirty">{{ saving ? 'Saving…' : 'Save changes' }}</button>
                </div>
            </form>
        </div>
        <PhotoEditor v-if="editing" :source="editing" :aspect="1" round :output-size="800" subject="portrait" title="Edit profile picture" @done="photo => saveAvatar(photo, message => (photoError = message))" @close="closeEditor" />
    </AccountLayout>
</template>

<style scoped>
/* Desktop: identity on the left, every editable detail in one card on the right, all on one screen.
   Narrower screens stack the two. */
.profile{display:grid;grid-template-columns:300px minmax(0,1fr);gap:18px;align-items:start}
.side{display:grid;gap:14px}
.card{padding:20px}
.identity{display:grid;justify-items:center;text-align:center;gap:10px}
.photo{position:relative;width:104px;height:104px;flex-shrink:0;border-radius:50%;background:#e3edd5;color:#214e3e;display:grid;place-items:center;font-size:34px;font-weight:800;overflow:hidden;box-shadow:0 0 0 4px #f1f5ec}
/* Absolutely positioned so tall photos fill the circle instead of stretching the grid row. */
.photo img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.who{min-width:0;width:100%}
.who h2{font-size:18px;margin:0;overflow-wrap:anywhere}
.who .muted{margin:2px 0 0;font-size:13px;overflow-wrap:anywhere}
.photo-actions{display:flex;gap:6px;flex-wrap:nowrap;justify-content:center}
.photo-actions .btn{padding:8px 11px;font-size:12px;white-space:nowrap}
.btn.icon{padding:8px 10px}
.btn.small{padding:8px 12px;font-size:12px}
.btn.disabled{opacity:.6;pointer-events:none}
.hint{margin:0;font-size:12px;line-height:1.45}
.details{display:grid;gap:0;padding:6px 22px 16px}
fieldset{border:0;margin:0;padding:16px 0;border-bottom:1px solid #eef2ea;min-width:0}
/* Floated so the fieldset's padding applies above it (a plain legend sits on the top border). */
legend{float:left;width:100%;display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:0;margin-bottom:12px;font-size:15px;font-weight:700;color:#172f2a}
legend .muted{font-size:12px;font-weight:400;line-height:1.4}
.emergency{color:#9c3e2d}
.grid{display:grid;gap:12px;clear:both}
.grid.two{grid-template-columns:repeat(2,minmax(0,1fr))}
.grid.three{grid-template-columns:repeat(3,minmax(0,1fr))}
.field input{padding:9px 11px}
.note{margin:8px 0 0;font-size:12px}
.save-bar{display:flex;align-items:center;justify-content:flex-end;gap:14px;padding-top:12px;flex-wrap:wrap}
.save-bar .error{margin:0 auto 0 0}
.unsaved{font-size:12px}
.error{color:#9c3e2d;font-size:13px}
.ok{display:inline-flex;align-items:center;gap:5px;color:#276c47;font-size:13px;font-weight:650}
.link{border:0;background:none;color:#214e3e;text-decoration:underline;font:inherit;cursor:pointer;padding:0}
.danger-zone{display:grid;gap:8px;border-color:#f0d6cc;padding:16px 18px}
.danger-zone h3{font-size:14px;margin:0;color:#9c3e2d}
.danger-zone .muted{font-size:12px;line-height:1.45;margin:0}
.danger-zone .btn{justify-self:start}
.delete-form{display:grid;gap:8px}
@media(max-width:900px){.profile{grid-template-columns:1fr}.side{display:contents}.identity{order:0}.details{order:1}.danger-zone{order:2}.identity{grid-template-columns:auto 1fr;justify-items:start;text-align:left;column-gap:16px}.identity .photo{grid-row:span 2;width:84px;height:84px;font-size:28px}.photo-actions,.hint,.identity .error{grid-column:1/-1;justify-content:flex-start}}
@media(max-width:640px){.grid.two,.grid.three{grid-template-columns:1fr}.save-bar .btn{width:100%}}
</style>
