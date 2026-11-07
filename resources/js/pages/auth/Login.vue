<script setup lang="ts">
import AuthLayout from '@/layouts/AuthLayout.vue';
import { Head, Link, useForm } from '@inertiajs/vue3';
import { LoaderCircle } from 'lucide-vue-next';

defineProps<{
    status?: string;
    canResetPassword: boolean;
}>();

const form = useForm({
    email: '',
    password: '',
    remember: false,
});

const submit = () => {
    form.post(route('login'), {
        onFinish: () => form.reset('password'),
    });
};
</script>

<template>
    <AuthLayout eyebrow="Welcome back" title="Log in to your account" description="Pick up where your barkada left off.">
        <Head title="Log in" />

        <p v-if="status" class="auth-status" role="status">{{ status }}</p>

        <form @submit.prevent="submit" class="auth-form">
            <div class="field">
                <label for="email">Email address</label>
                <input
                    id="email"
                    v-model="form.email"
                    type="email"
                    required
                    autofocus
                    autocomplete="email"
                    placeholder="email@example.com"
                    :aria-invalid="!!form.errors.email"
                    aria-describedby="email-error"
                />
                <p v-if="form.errors.email" id="email-error" class="field-error">{{ form.errors.email }}</p>
            </div>

            <div class="field">
                <div class="field-row">
                    <label for="password">Password</label>
                    <Link v-if="canResetPassword" :href="route('password.request')" class="auth-link">Forgot password?</Link>
                </div>
                <input
                    id="password"
                    v-model="form.password"
                    type="password"
                    required
                    autocomplete="current-password"
                    placeholder="Your password"
                    :aria-invalid="!!form.errors.password"
                    aria-describedby="password-error"
                />
                <p v-if="form.errors.password" id="password-error" class="field-error">{{ form.errors.password }}</p>
            </div>

            <label class="auth-check"><input v-model="form.remember" type="checkbox" /> Remember me</label>

            <button type="submit" class="btn auth-submit" :disabled="form.processing">
                <LoaderCircle v-if="form.processing" class="h-4 w-4 animate-spin" />
                Log in
            </button>

            <p class="auth-switch">New to BarKadaKaskas? <Link :href="route('register')" class="auth-link">Create an account</Link></p>
        </form>
    </AuthLayout>
</template>
