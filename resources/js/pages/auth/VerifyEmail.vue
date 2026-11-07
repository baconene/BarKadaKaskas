<script setup lang="ts">
import AuthLayout from '@/layouts/AuthLayout.vue';
import { Head, Link, useForm } from '@inertiajs/vue3';
import { LoaderCircle } from 'lucide-vue-next';

defineProps<{
    status?: string;
}>();

const form = useForm({});

const submit = () => {
    form.post(route('verification.send'));
};
</script>

<template>
    <AuthLayout eyebrow="One more step" title="Verify your email" description="We sent a verification link to your email. Click it to start planning trips.">
        <Head title="Email verification" />

        <p v-if="status === 'verification-link-sent'" class="auth-status" role="status">
            A new verification link has been sent to the email address you used to register.
        </p>

        <form @submit.prevent="submit" class="auth-form">
            <button type="submit" class="btn secondary auth-submit" :disabled="form.processing">
                <LoaderCircle v-if="form.processing" class="h-4 w-4 animate-spin" />
                Resend verification email
            </button>

            <p class="auth-switch">Wrong account? <Link :href="route('logout')" method="post" as="button" class="auth-link">Log out</Link></p>
        </form>
    </AuthLayout>
</template>
