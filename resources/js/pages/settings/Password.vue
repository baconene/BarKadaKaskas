<script setup lang="ts">
import { Head, useForm } from '@inertiajs/vue3';
import { ref } from 'vue';
import AccountLayout from '@/layouts/AccountLayout.vue';

const form = useForm({ current_password: '', password: '', password_confirmation: '' });
const saved = ref(false);
function updatePassword() {
    form.put(route('password.update'), {
        preserveScroll: true,
        onSuccess: () => { form.reset(); saved.value = true; setTimeout(() => { saved.value = false; }, 2500); },
        onError: errors => {
            if (errors.password) form.reset('password', 'password_confirmation');
            if (errors.current_password) form.reset('current_password');
        },
    });
}
</script>

<template>
    <Head title="Password" />
    <AccountLayout active="password">
        <form class="card form-grid" aria-labelledby="password-title" @submit.prevent="updatePassword">
            <div class="full"><h2 id="password-title">Change password</h2><p class="muted">Use a long password you don't use anywhere else.</p></div>
            <label class="field full">Current password<input v-model="form.current_password" type="password" autocomplete="current-password" required /></label>
            <label class="field">New password<input v-model="form.password" type="password" autocomplete="new-password" required /></label>
            <label class="field">Confirm new password<input v-model="form.password_confirmation" type="password" autocomplete="new-password" required /></label>
            <p v-if="form.hasErrors" class="error full" role="alert">{{ Object.values(form.errors).join(' ') }}</p>
            <div class="row full"><button class="btn" :disabled="form.processing">{{ form.processing ? 'Saving…' : 'Save password' }}</button><span v-if="saved" class="ok" role="status">Password updated</span></div>
        </form>
    </AccountLayout>
</template>

<style scoped>
.error{color:#9c3e2d;font-size:13px}
.ok{color:#276c47;font-size:13px;font-weight:650}
</style>
